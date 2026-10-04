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
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Off-platform holdings the customer declares themselves (ADR-0301 D1), proxying
 * `openbank-wealth-service` with the edge M2M token. These are the write side of the
 * declared-holdings branch that `GET /net-worth` already reads; without them that branch is empty
 * for every customer (#11966).
 *
 * Three rules carry the security of this resource, and each exists because wealth-service, an
 * M2M-only service, deliberately does not enforce it:
 *
 *  1. **The owner is the token's party** ([CustomerPartyResolver], including a verified
 *     `X-Acting-For`, so a holding declared while acting for a company belongs to that company and
 *     appears in its net worth). Nothing the client sends names an owner.
 *  2. **Every by-id route proves ownership first.** wealth-service reads, revalues and withdraws a
 *     holding by id alone. The edge reads it, compares `ownerPartyId` with the caller and answers
 *     404 on a mismatch — the same answer as a holding that does not exist, so the route cannot be
 *     used to probe which ids are real.
 *  3. **A customer can only assert `CUSTOMER_DECLARED`.** The source is set here, never read from
 *     the body, so a customer cannot label their own figure as an expert appraisal or a market
 *     reference. A customer revaluation of an appraised holding therefore downgrades its label,
 *     which is the honest outcome: the new number is the customer's.
 *
 * Upstream error bodies are never forwarded. wealth-service writes them for operators and one of
 * them names the loan a holding is pledged to. Input is validated here first ([HoldingInput]), so
 * an upstream 400 is unexpected, and a 409 maps to a fixed code.
 */
@Path("/customer/v1/holdings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CustomerHoldingsResource(private val upstream: UpstreamClient, private val parties: CustomerPartyResolver) {
    @ConfigProperty(name = "openbank.edge.wealth-service-url")
    lateinit var wealthServiceUrl: String

    @Context
    lateinit var requestHeaders: HttpHeaders

    /** The caller's active and pledged holdings. Withdrawn ones are not returned by wealth-service. */
    @GET
    @Authorize(action = "customer.wealth.holding.read", resource = "")
    @Blocking
    fun list(): Response {
        val party = party()
        val response = upstream.get(holdingsUrl(), party.toString())
        if (response.status != OK) return EdgeJson.upstreamFailure(response, SERVICE)
        val node = EdgeJson.parse(response)?.takeIf { it.isArray } ?: return badUpstream()
        return EdgeJson.ok(node.filter { it.text("ownerPartyId") == party.toString() }.map(HoldingProjection::holding))
    }

    /** Declare a holding. 201 with the projection; 400 names the first invalid field. */
    @POST
    @Authorize(action = "customer.wealth.holding.declare", resource = "")
    @Blocking
    fun declare(body: String?): Response {
        val declaration = HoldingInput.declaration(EdgeJson.parseObject(body))
            .getOrElse { return invalid(it.message ?: "invalid holding") }
        val response = upstream.post(holdingsUrl(), party().toString(), EdgeJson.mapper.writeValueAsString(declaration))
        return when (response.status) {
            CREATED, OK -> projected(response, CREATED)
            // The natural key (owner, type, externalReference) is unique upstream.
            CONFLICT -> EdgeJson.error(
                CONFLICT,
                "a holding of this type with this externalReference is already declared",
                mapOf("code" to "DUPLICATE_HOLDING"),
            )
            else -> EdgeJson.upstreamFailure(response, SERVICE)
        }
    }

    @GET
    @Path("/{holdingId}")
    @Authorize(action = "customer.wealth.holding.read", resource = "")
    @Blocking
    fun get(@PathParam("holdingId") id: String?): Response =
        owned(id) { holding, _, _ -> EdgeJson.ok(HoldingProjection.holding(holding)) }

    /** Every value ever asserted for the holding, newest first. Append-only upstream. */
    @GET
    @Path("/{holdingId}/valuations")
    @Authorize(action = "customer.wealth.holding.read", resource = "")
    @Blocking
    fun valuations(@PathParam("holdingId") id: String?): Response = owned(id) { _, party, holdingId ->
        val response = upstream.get("${holdingsUrl()}/$holdingId/valuations", party.toString())
        if (response.status != OK) return@owned EdgeJson.upstreamFailure(response, SERVICE)
        val node = EdgeJson.parse(response)?.takeIf { it.isArray } ?: return@owned badUpstream()
        EdgeJson.ok(node.map(HoldingProjection::valuation))
    }

    /** Restate the value. The previous value stays in the history; the label becomes CUSTOMER_DECLARED. */
    @PUT
    @Path("/{holdingId}/valuation")
    @Authorize(action = "customer.wealth.holding.revalue", resource = "")
    @Blocking
    fun revalue(@PathParam("holdingId") id: String?, body: String?): Response {
        val valuation = HoldingInput.valuation(EdgeJson.parseObject(body)?.path("valuation"))
            ?: return invalid(HoldingInput.VALUATION_RULE)
        return owned(id) { _, party, holdingId ->
            val response = upstream.put(
                "${holdingsUrl()}/$holdingId/valuation",
                party.toString(),
                EdgeJson.mapper.writeValueAsString(mapOf("valuation" to valuation)),
            )
            if (response.status == OK) projected(response, OK) else lifecycleFailure(response)
        }
    }

    /** Withdraw the holding. 409 HOLDING_LOCKED while lending holds it as collateral. */
    @DELETE
    @Path("/{holdingId}")
    @Authorize(action = "customer.wealth.holding.withdraw", resource = "")
    @Blocking
    fun withdraw(@PathParam("holdingId") id: String?): Response = owned(id) { _, party, holdingId ->
        val response = upstream.delete("${holdingsUrl()}/$holdingId", party.toString())
        if (response.status == OK) projected(response, OK) else lifecycleFailure(response)
    }

    /**
     * Runs [action] only for a holding the caller owns. A malformed id, an unknown id and another
     * party's id all answer the same 404, and in none of those cases does [action] run. [action]
     * receives the CANONICAL id: `UUID.fromString` also accepts forms like `1-1-1-1-1`, and the raw
     * path segment must never be what goes upstream.
     */
    private inline fun owned(id: String?, action: (JsonNode, UUID, UUID) -> Response): Response {
        val holdingId = id?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return notFound()
        val party = party()
        val response = upstream.get("${holdingsUrl()}/$holdingId", party.toString())
        if (response.status == NOT_FOUND) return notFound()
        if (response.status != OK) return EdgeJson.upstreamFailure(response, SERVICE)
        val holding = EdgeJson.parse(response)?.takeIf { it.isObject } ?: return badUpstream()
        if (holding.text("ownerPartyId") != party.toString()) return notFound()
        return action(holding, party, holdingId)
    }

    private fun party(): UUID = parties.resolve(
        if (this::requestHeaders.isInitialized) {
            requestHeaders.getHeaderString(CustomerEdgeResource.ACTING_FOR_HEADER)
        } else {
            null
        },
    )

    private fun holdingsUrl() = "${wealthServiceUrl.trimEnd('/')}$API"
}

/**
 * What a customer may send, validated before anything goes upstream. A failure carries a message
 * naming the first invalid field. The valuation source is never read from the input.
 */
internal object HoldingInput {
    const val VALUATION_RULE =
        "valuation needs a non-negative amount, an ISO 4217 currency and a valuedAt date not in the future"
    private const val MAX_LABEL = 200
    private const val MAX_EXTERNAL_REFERENCE = 128
    private const val CUSTOMER_DECLARED = "CUSTOMER_DECLARED"
    private val CURRENCY = Regex("^[A-Z]{3}$")

    /** Mirrors wealth-service's `HoldingType`; an unknown type never goes upstream. */
    private val HOLDING_TYPES = listOf(
        "REAL_ESTATE",
        "COLLECTIBLE",
        "EXTERNAL_SECURITIES",
        "EXTERNAL_COMPANY_STAKE",
        "VEHICLE",
        "EXTERNAL_DEPOSIT",
        "PRIVATE_CLAIM",
        "MORTGAGE",
        "CONSUMER_CREDIT",
        "PRIVATE_DEBT",
        "OTHER_LIABILITY",
    )

    fun declaration(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val type = requireNotNull(node.text("holdingType")?.takeIf { it in HOLDING_TYPES }) {
            "holdingType must be one of ${HOLDING_TYPES.joinToString()}"
        }
        val label = requireNotNull(node.text("label")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LABEL }) {
            "label is required and at most $MAX_LABEL characters"
        }
        val externalReference = node.text("externalReference")?.trim()?.takeIf { it.isNotEmpty() }
        require(externalReference == null || externalReference.length <= MAX_EXTERNAL_REFERENCE) {
            "externalReference is at most $MAX_EXTERNAL_REFERENCE characters"
        }
        mapOf(
            "holdingType" to type,
            "label" to label,
            "valuation" to requireNotNull(valuation(node.path("valuation"))) { VALUATION_RULE },
            "ownershipShare" to ownershipShare(node),
            "externalReference" to externalReference,
        )
    }

    /** The valuation a customer may assert: amount, currency and date. The source is fixed here. */
    fun valuation(node: JsonNode?): Map<String, Any>? {
        if (node == null || !node.isObject) return null
        val amount = node.decimalString("amount")?.let(::BigDecimal)?.takeIf { it >= BigDecimal.ZERO } ?: return null
        val currency = node.text("currency")?.takeIf { CURRENCY.matches(it) } ?: return null
        val valuedAt = node.text("valuedAt")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { !it.isAfter(LocalDate.now()) } ?: return null
        return mapOf(
            "amount" to amount,
            "currency" to currency,
            "valuedAt" to valuedAt.toString(),
            "source" to CUSTOMER_DECLARED,
        )
    }

    private fun ownershipShare(node: JsonNode): BigDecimal {
        if (!node.has("ownershipShare")) return BigDecimal.ONE
        return requireNotNull(
            node.decimalString("ownershipShare")?.let(::BigDecimal)?.takeIf {
                it > BigDecimal.ZERO &&
                    it <= BigDecimal.ONE
            },
        ) { "ownershipShare must be greater than 0 and at most 1" }
    }
}

/**
 * The customer contract. `ownerPartyId` is the caller and adds nothing; `pledgedToLoanId` and
 * `appraiserReference` are bank-side references, so the app sees `pledged` instead.
 */
internal object HoldingProjection {
    fun holding(h: JsonNode): Map<String, Any?> = mapOf(
        "holdingId" to h.text("holdingId"),
        "holdingType" to h.text("holdingType"),
        "isLiability" to h.path("isLiability").asBoolean(false),
        "label" to h.text("label"),
        "amount" to h.decimalString("amount"),
        "currency" to h.text("currency"),
        "valuedAt" to h.text("valuedAt"),
        "valuationSource" to h.text("valuationSource"),
        "valuationAgeDays" to h.path("valuationAgeDays").takeIf { it.isIntegralNumber }?.longValue(),
        "ownershipShare" to h.decimalString("ownershipShare"),
        "attributableAmount" to h.decimalString("attributableAmount"),
        "externalReference" to h.text("externalReference"),
        "status" to h.text("status"),
        "pledged" to (h.text("status") == "PLEDGED"),
        "createdAt" to h.text("createdAt"),
        "updatedAt" to h.text("updatedAt"),
    )

    fun valuation(v: JsonNode): Map<String, Any?> = mapOf(
        "amount" to v.decimalString("amount"),
        "currency" to v.text("currency"),
        "valuedAt" to v.text("valuedAt"),
        "valuationSource" to v.text("source"),
        "recordedAt" to v.text("recordedAt"),
    )
}

private const val API = "/api/v1/holdings"
private const val SERVICE = "wealth service"
private const val OK = 200
private const val CREATED = 201
private const val BAD_REQUEST = 400
private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val BAD_GATEWAY = 502

private fun projected(response: Response, status: Int): Response =
    EdgeJson.parse(response)?.takeIf { it.isObject }?.let { EdgeJson.ok(HoldingProjection.holding(it), status) }
        ?: badUpstream()

/** A revalue or withdraw refused by the holding's lifecycle; the upstream message is never forwarded. */
private fun lifecycleFailure(response: Response): Response = if (response.status == CONFLICT) {
    EdgeJson.error(CONFLICT, "the holding cannot change in its current state", mapOf("code" to "HOLDING_LOCKED"))
} else {
    EdgeJson.upstreamFailure(response, SERVICE)
}

private fun invalid(message: String) = EdgeJson.error(BAD_REQUEST, message)

private fun notFound() = EdgeJson.error(NOT_FOUND, "holding not found")

private fun badUpstream() = EdgeJson.error(BAD_GATEWAY, "unexpected $SERVICE response")
