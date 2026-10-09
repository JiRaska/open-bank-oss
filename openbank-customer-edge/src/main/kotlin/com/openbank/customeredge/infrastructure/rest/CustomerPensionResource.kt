// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.customeredge.infrastructure.rest.EdgeJson.decimalString
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.util.UUID

/**
 * The customer's pension lifecycle (ADR-0334 S6): discover, simulate, onboard, transfer in, manage,
 * terminate early and request payout, proxying `openbank-pension-service` (participant side) and
 * reading `openbank-pension-fund-service` (unit register) with the edge M2M token.
 *
 * Security rules, each because an upstream does not or cannot enforce it for a customer:
 *
 *  1. **The participant is the token's own party.** Pension contracts are personal, so
 *     `X-Acting-For` is not honoured here — a contract opened while acting for a company would
 *     belong to nobody who can legally hold it. Nothing in a body names a participant.
 *  2. **Every by-id route proves ownership first** by reading the contract with the party header
 *     (pension-service answers 404 for another party's contract) and comparing
 *     `participantPartyId`. Unknown, malformed and foreign ids all answer the same 404, and
 *     pension-fund-service — which serves holdings by contract id to any M2M caller — is only
 *     reached after that check.
 *  3. **Valuation inputs are never the customer's.** pension-service's early-termination preview
 *     takes `currentValue` from its caller (S1: the unit register is not wired to it yet). The edge
 *     derives it from the unit register itself, so a customer cannot price their own surrender.
 *  4. **Upstream error bodies are never forwarded** ([EdgeJson.upstreamFailure]); a 400 from a pack
 *     rule maps to a fixed code, a 409 to `INVALID_CONTRACT_STATE`.
 *
 * Routes marked "backend pending" in openapi.yaml proxy pension-service paths that the backend
 * slices S2/S3/S5 (#12350) have not shipped yet; until they do they answer 404 through rule 4.
 */
@Path("/customer/v1/pension")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
@Suppress("TooManyFunctions")
class CustomerPensionResource(private val upstream: UpstreamClient, private val parties: CustomerPartyResolver) {
    @ConfigProperty(name = "openbank.edge.pension-service-url")
    lateinit var pensionServiceUrl: String

    @ConfigProperty(name = "openbank.edge.pension-fund-service-url")
    lateinit var fundServiceUrl: String

    @ConfigProperty(
        name = "openbank.edge.product-catalog-url",
        defaultValue = "http://product-catalog.accounts.svc:8104",
    )
    lateinit var catalogUrl: String

    /** Published DPS/DIP offerings from the product catalog's retirement pack. */
    @GET
    @Path("/products")
    @Authorize(action = "customer.pension.product.read", resource = "")
    @Blocking
    fun products(): Response {
        val catalog = catalogUrl.trimEnd('/')
        val offerings = upstream.get("$catalog/api/v2/offerings")
        if (offerings.status != OK) return EdgeJson.upstreamFailure(offerings, CATALOG)
        val ids = EdgeJson.parse(offerings)?.takeIf { it.isArray } ?: return badUpstream(CATALOG)
        val products = ids.mapNotNull { it.text("id") }.take(MAX_OFFERINGS).mapNotNull { id ->
            val revision = upstream.get("$catalog/api/v2/products/$id")
            if (revision.status != OK) return@mapNotNull null
            EdgeJson.parse(revision)?.takeIf { it.isObject }?.let { PensionProjection.product(id, it) }
        }
        return EdgeJson.ok(products)
    }

    /** Retirement projection per strategy. Backend pending (S1 has no simulation route). */
    @POST
    @Path("/simulations")
    @Authorize(action = "customer.pension.simulate", resource = "")
    @Blocking
    fun simulate(body: String?): Response {
        val input = PensionInput.simulation(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val response = upstream.post("${api()}/simulations", party().toString(), json(input))
        return if (response.status == OK) passThrough(response) else failure(response)
    }

    /** The caller's contracts. Backend pending (S1 reads by id only). */
    @GET
    @Path("/contracts")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun contracts(): Response {
        val party = party().toString()
        val response = upstream.get("${api()}/contracts", party)
        if (response.status != OK) return failure(response)
        val node = EdgeJson.parse(response)?.takeIf { it.isArray } ?: return badUpstream(SERVICE)
        return EdgeJson.ok(node.filter { it.text("participantPartyId") == party }.map(PensionProjection::contract))
    }

    /** Onboarding step 1: a DRAFT contract under the pack in force today. */
    @POST
    @Path("/contracts")
    @Authorize(action = "customer.pension.contract.create", resource = "")
    @Blocking
    fun create(body: String?, @HeaderParam(IDEMPOTENCY) key: String?): Response {
        val input = PensionInput.createContract(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val response = upstream.post("${api()}/contracts", party().toString(), json(input), key)
        return if (response.status == CREATED) contractOf(response, CREATED) else failure(response)
    }

    /** Contract overview: the contract plus its unit holdings at the latest published NAV. */
    @GET
    @Path("/contracts/{id}")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun contract(@PathParam("id") id: String?): Response = owned(id) { contract, _, contractId ->
        val valuation = valuation(contractId)
        EdgeJson.ok(
            PensionProjection.contract(contract) + ("valuation" to valuation?.let(PensionProjection::valuation)),
        )
    }

    /** Priced unit transactions of the contract, newest first. */
    @GET
    @Path("/contracts/{id}/transactions")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun transactions(@PathParam("id") id: String?): Response = owned(id) { _, _, contractId ->
        val response = upstream.get("${fund()}/contracts/$contractId/transactions")
        if (response.status != OK) return@owned EdgeJson.upstreamFailure(response, FUND)
        val node = EdgeJson.parse(response)?.takeIf { it.isArray } ?: return@owned badUpstream(FUND)
        EdgeJson.ok(node.map(PensionProjection::transaction))
    }

    /** Onboarding: sign and submit the draft (DRAFT -> PENDING_ACTIVATION). */
    @POST
    @Path("/contracts/{id}/submit")
    @Authorize(action = "customer.pension.contract.submit", resource = "")
    @Blocking
    fun submit(@PathParam("id") id: String?, @HeaderParam(IDEMPOTENCY) key: String?): Response =
        transition(id, "submit", key)

    /** Pause contributions (ACTIVE -> SUSPENDED). */
    @POST
    @Path("/contracts/{id}/pause")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun pause(@PathParam("id") id: String?, @HeaderParam(IDEMPOTENCY) key: String?): Response =
        transition(id, "suspend", key)

    /** Resume contributions (SUSPENDED -> ACTIVE). */
    @POST
    @Path("/contracts/{id}/resume")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun resume(@PathParam("id") id: String?, @HeaderParam(IDEMPOTENCY) key: String?): Response =
        transition(id, "resume", key)

    /** Change strategy; units switch at the next NAV. */
    @PUT
    @Path("/contracts/{id}/strategy")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun strategy(@PathParam("id") id: String?, body: String?): Response {
        val input = PensionInput.strategy(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return owned(id) { _, party, contractId ->
            val response = upstream.put("${api()}/contracts/$contractId/strategy", party.toString(), json(input))
            if (response.status == OK) contractOf(response, OK) else failure(response)
        }
    }

    /** Change the contribution schedule. Backend pending (S3). */
    @PUT
    @Path("/contracts/{id}/contribution")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun contribution(@PathParam("id") id: String?, body: String?): Response {
        val input = runCatching { PensionInput.schedule(EdgeJson.parseObject(body)) }.getOrElse { return invalid(it) }
        return owned(id) { _, party, contractId ->
            val response = upstream.put("${api()}/contracts/$contractId/schedule", party.toString(), json(input))
            if (response.status == OK) contractOf(response, OK) else failure(response)
        }
    }

    /** Replace the beneficiary designations. Backend pending (S5). */
    @PUT
    @Path("/contracts/{id}/beneficiaries")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun beneficiaries(@PathParam("id") id: String?, body: String?): Response {
        val input = PensionInput.beneficiaries(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return owned(id) { _, party, contractId ->
            val response = upstream.put("${api()}/contracts/$contractId/beneficiaries", party.toString(), json(input))
            if (response.status == OK) contractOf(response, OK) else failure(response)
        }
    }

    /** Contributions, incentives and deductible amount for a tax year (S3 funding route; backend pending). */
    @GET
    @Path("/contracts/{id}/tax-summary")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun taxSummary(@PathParam("id") id: String?, @QueryParam("year") year: String?): Response {
        val taxYear = PensionInput.taxYear(year) ?: return invalid("year must be a past or current calendar year")
        return owned(id) { _, party, contractId ->
            val response = upstream.get("${api()}/funding/contracts/$contractId/tax-years/$taxYear", party.toString())
            if (response.status == OK) passThrough(response) else failure(response)
        }
    }

    /** Onboarding by transfer-in from a ceding provider. Backend pending (S2). */
    @POST
    @Path("/contracts/{id}/transfers-in")
    @Authorize(action = "customer.pension.transfer.request", resource = "")
    @Blocking
    fun transferIn(@PathParam("id") id: String?, body: String?, @HeaderParam(IDEMPOTENCY) key: String?): Response {
        val input = PensionInput.transferIn(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return owned(id) { _, party, contractId ->
            val response = upstream.post(
                "${api()}/contracts/$contractId/transfers-in",
                party.toString(),
                json(input),
                key,
            )
            if (response.status == CREATED || response.status == OK) passThrough(response) else failure(response)
        }
    }

    /** Surrender preview. The current value comes from the unit register, never from the caller. */
    @POST
    @Path("/contracts/{id}/early-termination/preview")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun earlyTerminationPreview(@PathParam("id") id: String?, @HeaderParam(IDEMPOTENCY) key: String?): Response =
        earlyTermination(id, confirm = false, key = key)

    /** Early-termination notice: the contract moves to TERMINATING. */
    @POST
    @Path("/contracts/{id}/early-termination/notice")
    @Authorize(action = "customer.pension.contract.terminate", resource = "")
    @Blocking
    fun earlyTerminationNotice(@PathParam("id") id: String?, @HeaderParam(IDEMPOTENCY) key: String?): Response =
        earlyTermination(id, confirm = true, key = key)

    /** Binding payout quote for a form (S5 exit route; backend pending). */
    @POST
    @Path("/contracts/{id}/payouts")
    @Authorize(action = "customer.pension.payout.request", resource = "")
    @Blocking
    fun payout(@PathParam("id") id: String?, body: String?, @HeaderParam(IDEMPOTENCY) key: String?): Response {
        val input = PensionInput.payoutQuote(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return owned(id) { _, party, contractId ->
            val response = upstream.post(
                "${api()}/contracts/$contractId/exit/payouts/quote",
                party.toString(),
                json(input),
                key,
            )
            if (response.status == CREATED || response.status == OK) passThrough(response) else failure(response)
        }
    }

    /** Confirm a quoted payout with the completed SCA challenge (S5 exit route; backend pending). */
    @POST
    @Path("/contracts/{id}/payouts/{payoutId}/confirm")
    @Authorize(action = "customer.pension.payout.request", resource = "")
    @Blocking
    fun confirmPayout(
        @PathParam("id") id: String?,
        @PathParam("payoutId") payoutId: String?,
        body: String?,
        @HeaderParam(IDEMPOTENCY) key: String?,
    ): Response {
        val input = PensionInput.payoutConfirmation(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val payout = payoutId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return notFound()
        return owned(id) { _, party, contractId ->
            val response = upstream.post(
                "${api()}/contracts/$contractId/exit/payouts/$payout/confirm",
                party.toString(),
                json(input),
                key,
            )
            if (response.status == OK) passThrough(response) else failure(response)
        }
    }

    private fun transition(id: String?, action: String, key: String?): Response = owned(id) { _, party, contractId ->
        val response = upstream.post("${api()}/contracts/$contractId/$action", party.toString(), "{}", key)
        if (response.status == OK) contractOf(response, OK) else failure(response)
    }

    private fun earlyTermination(id: String?, confirm: Boolean, key: String?): Response =
        owned(id) { _, party, contractId ->
            val valuation = valuation(contractId) ?: return@owned EdgeJson.error(
                SERVICE_UNAVAILABLE,
                "the contract cannot be valued right now",
                mapOf("code" to "VALUATION_UNAVAILABLE"),
            )
            val value = currentValue(valuation) ?: return@owned EdgeJson.error(
                CONFLICT,
                "part of the contract has no published unit price yet",
                mapOf("code" to "VALUATION_INCOMPLETE"),
            )
            // incentivesReceived stays empty until S3's incentive register answers it; the preview
            // then says so in `incentiveHistoryIncluded` rather than presenting a clawback of zero.
            val body = mapOf(
                "currentValue" to value,
                "incentivesReceived" to emptyMap<String, Any>(),
                "confirm" to confirm,
            )
            val response =
                upstream.post("${api()}/contracts/$contractId/early-termination", party.toString(), json(body), key)
            if (response.status != OK) return@owned failure(response)
            val node = EdgeJson.parse(response)?.takeIf { it.isObject } ?: return@owned badUpstream(SERVICE)
            EdgeJson.ok(PensionProjection.earlyTermination(node) + ("incentiveHistoryIncluded" to false))
        }

    /** Sum of holding values; null when any holding has no published NAV (a partial sum would understate). */
    private fun currentValue(valuation: JsonNode): BigDecimal? {
        val holdings = valuation.path("holdings").filter { it.isObject }
        if (holdings.any { it.decimalString("value") == null }) return null
        return holdings.sumOf { BigDecimal(it.decimalString("value")) }
    }

    private fun valuation(contractId: UUID): JsonNode? {
        val response = upstream.get("${fund()}/contracts/$contractId/holdings")
        if (response.status != OK) return null
        return EdgeJson.parse(response)?.takeIf { it.isObject }
    }

    /**
     * Runs [action] only for a contract the caller holds; see rule 2. [action] receives the
     * CANONICAL id, never the raw path segment.
     */
    private inline fun owned(id: String?, action: (JsonNode, UUID, UUID) -> Response): Response {
        val contractId = id?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return notFound()
        val party = party()
        val response = upstream.get("${api()}/contracts/$contractId", party.toString())
        if (response.status == NOT_FOUND) return notFound()
        if (response.status != OK) return EdgeJson.upstreamFailure(response, SERVICE)
        val contract = EdgeJson.parse(response)?.takeIf { it.isObject } ?: return badUpstream(SERVICE)
        if (contract.text("participantPartyId") != party.toString()) return notFound()
        return action(contract, party, contractId)
    }

    /** Rule 1: always the token's own party; `X-Acting-For` is deliberately not passed. */
    private fun party(): UUID = parties.resolve(null)

    private fun api() = "${pensionServiceUrl.trimEnd('/')}/api/v1/pension"

    private fun fund() = "${fundServiceUrl.trimEnd('/')}/api/v1"

    private fun json(value: Any) = EdgeJson.mapper.writeValueAsString(value)

    private fun contractOf(response: Response, status: Int): Response =
        EdgeJson.parse(response)?.takeIf { it.isObject }?.let { EdgeJson.ok(PensionProjection.contract(it), status) }
            ?: badUpstream(SERVICE)

    private fun passThrough(response: Response): Response =
        EdgeJson.parse(response)?.let { EdgeJson.ok(it, response.status) } ?: badUpstream(SERVICE)

    private fun failure(response: Response): Response = when (response.status) {
        BAD_REQUEST -> EdgeJson.error(
            BAD_REQUEST,
            "refused by the pension rules",
            mapOf(
                "code" to "PENSION_RULE_REFUSED",
            ),
        )
        CONFLICT -> EdgeJson.error(
            CONFLICT,
            "the contract cannot do this in its current state",
            mapOf("code" to "INVALID_CONTRACT_STATE"),
        )
        else -> EdgeJson.upstreamFailure(response, SERVICE)
    }

    private companion object {
        const val SERVICE = "pension service"
        const val FUND = "pension fund service"
        const val CATALOG = "product catalog"
        const val IDEMPOTENCY = "Idempotency-Key"
        const val MAX_OFFERINGS = 50
        const val OK = 200
        const val CREATED = 201
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val BAD_GATEWAY = 502
        const val SERVICE_UNAVAILABLE = 503

        fun invalid(error: Throwable) = invalid(error.message ?: "invalid request")

        fun invalid(message: String) = EdgeJson.error(BAD_REQUEST, message)

        fun notFound() = EdgeJson.error(NOT_FOUND, "pension contract not found")

        fun badUpstream(service: String) = EdgeJson.error(BAD_GATEWAY, "unexpected $service response")
    }
}
