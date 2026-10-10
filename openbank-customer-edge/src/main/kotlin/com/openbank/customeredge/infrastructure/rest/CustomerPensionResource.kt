// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.identifiers.Ids
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
import java.time.LocalDate
import java.util.UUID

/**
 * The customer's pension lifecycle (ADR-0334 S6): discover, simulate, onboard (S2 application
 * flow), transfer in, manage, terminate early and take a payout (S5 quote → SCA sign → confirm),
 * proxying `openbank-pension-service` API 1.2.0 (participant side). The edge never calls
 * `openbank-pension-fund-service`: participant holdings there are readable only by staff and
 * pension-service's own client (pension_fund_rest_ext.rego), and its NetworkPolicy admits only the
 * `pension` namespace for that surface.
 *
 * Security rules, each because an upstream does not or cannot enforce it for a customer:
 *
 *  1. **The participant is the token's own party.** Pension contracts are personal, so
 *     `X-Acting-For` is not honoured here. Nothing in a body names a participant.
 *  2. **Eligibility facts come from the party record, never from the app.** Birth date and
 *     residency country are read from party-service for the token's party and sent upstream; an
 *     incomplete record refuses the application (422 `PARTY_PROFILE_INCOMPLETE`) rather than
 *     sending a null residency the jurisdiction pack would have to guess about.
 *  3. **Every by-id contract route proves ownership first** by reading the contract with the party
 *     header (pension-service answers 404 for another party's contract) and comparing
 *     `participantPartyId`. Unknown, malformed and foreign ids all answer the same 404.
 *     Application routes are party-scoped by pension-service itself.
 *  4. **Upstream error bodies are never forwarded** ([EdgeJson.upstreamFailure]); a 400 from a pack
 *     rule maps to a fixed code, a 409 to `INVALID_CONTRACT_STATE`.
 *  5. **Every state change needs SCA.** Where pension-service spends the challenge itself
 *     (application sign, termination sign, payout confirm, payout account change — each bound to
 *     the quote or document it signs) the edge forwards it and refuses a request without one;
 *     everywhere else the edge consumes it bound to the exact upstream payload (see [scaGate]).
 *  6. **Every POST carries the caller's `Idempotency-Key`** upstream; a state-changing POST without
 *     one is refused (400) before anything goes upstream, so a retry can never act twice.
 */
@Path("/customer/v1/pension")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
@Suppress("TooManyFunctions")
class CustomerPensionResource(private val upstream: UpstreamClient, private val parties: CustomerPartyResolver) {
    @ConfigProperty(name = "openbank.edge.pension-service-url")
    lateinit var pensionServiceUrl: String

    @ConfigProperty(name = "openbank.edge.party-service-url")
    lateinit var partyServiceUrl: String

    @ConfigProperty(
        name = "openbank.edge.product-catalog-url",
        defaultValue = "http://product-catalog.accounts.svc:8104",
    )
    lateinit var catalogUrl: String

    @ConfigProperty(name = "openbank.edge.sca-service-url")
    lateinit var scaServiceUrl: String

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

    /** Illustrative retirement projection per strategy (binds nothing, so no key is required). */
    @POST
    @Path("/simulations")
    @Authorize(action = "customer.pension.simulate", resource = "")
    @Blocking
    fun simulate(body: String?): Response {
        val input = PensionInput.simulation(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        // A fresh key per call: a simulation changes nothing, so replaying an earlier answer would
        // only ever serve stale assumptions.
        val response = upstream.post(
            "${api()}/simulations",
            party().toString(),
            json(input),
            Ids.randomId().toString(),
        )
        return if (response.status == OK) passThrough(response) else failure(response)
    }

    /** The caller's contracts. */
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

    /** Onboarding step 1: open an application (eligibility and KYC are checked at once). */
    @POST
    @Path("/applications")
    @Authorize(action = "customer.pension.contract.create", resource = "")
    @Blocking
    fun startApplication(body: String?, @HeaderParam("Idempotency-Key") key: String?): Response {
        val input = PensionInput.application(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return openApplication("${api()}/onboarding/applications", input, key)
    }

    /** Onboarding by transfer-in from a ceding provider: an application of kind TRANSFER_IN. */
    @POST
    @Path("/transfers-in")
    @Authorize(action = "customer.pension.transfer.request", resource = "")
    @Blocking
    fun transferIn(body: String?, @HeaderParam("Idempotency-Key") key: String?): Response {
        val input = PensionInput.transferIn(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return openApplication("${api()}/contracts/transfers-in", input + ("kind" to "TRANSFER_IN"), key)
    }

    @GET
    @Path("/applications/{applicationId}")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun application(@PathParam("applicationId") id: String?): Response {
        val applicationId = uuid(id) ?: return applicationNotFound()
        return applicationResult(upstream.get("${api()}/onboarding/applications/$applicationId", party().toString()))
    }

    @GET
    @Path("/applications/{applicationId}/recommendation")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun recommendation(@PathParam("applicationId") id: String?): Response {
        val applicationId = uuid(id) ?: return applicationNotFound()
        return applicationResult(
            upstream.get("${api()}/onboarding/applications/$applicationId/recommendation", party().toString()),
        )
    }

    @POST
    @Path("/applications/{applicationId}/questionnaire")
    @Authorize(action = "customer.pension.contract.create", resource = "")
    @Blocking
    fun questionnaire(
        @PathParam("applicationId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
    ): Response {
        val input = PensionInput.questionnaire(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return applicationStep(id, "questionnaire", input, key)
    }

    @POST
    @Path("/applications/{applicationId}/strategy")
    @Authorize(action = "customer.pension.contract.create", resource = "")
    @Blocking
    fun chooseStrategy(
        @PathParam("applicationId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
    ): Response {
        val input = PensionInput.chooseStrategy(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return applicationStep(id, "strategy", input, key)
    }

    @POST
    @Path("/applications/{applicationId}/kid/accept")
    @Authorize(action = "customer.pension.contract.create", resource = "")
    @Blocking
    fun acceptKid(
        @PathParam("applicationId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
    ): Response {
        val input = PensionInput.kidAcceptance(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return applicationStep(id, "kid/accept", input, key)
    }

    /**
     * Sign the application. pension-service spends the challenge itself, bound to the application
     * and its key-information document (S2); the edge consuming it first would spend its single use.
     */
    @POST
    @Path("/applications/{applicationId}/sign")
    @Authorize(action = "customer.pension.contract.submit", resource = "")
    @Blocking
    fun signApplication(
        @PathParam("applicationId") id: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val challenge = ScaConsume.challengeId(sca) ?: return ScaConsume.required()
        return applicationStep(id, "sign", mapOf("scaChallengeId" to challenge.toString()), key)
    }

    /** Withdraw a signed application within the cooling-off period; the contract closes. */
    @POST
    @Path("/applications/{applicationId}/withdraw")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun withdraw(
        @PathParam("applicationId") id: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val applicationId = uuid(id) ?: return applicationNotFound()
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        val party = party()
        scaGate(sca, party, "withdraw", applicationId, emptyMap<String, Any>())?.let { return it }
        return applicationResult(
            upstream.post(
                "${api()}/onboarding/applications/$applicationId/withdraw",
                party.toString(),
                "{}",
                idempotencyKey,
            ),
        )
    }

    /**
     * Contract overview, with the participant valuation pension-service serves for it. The edge
     * never reads the unit register itself; a valuation pension-service cannot give right now is
     * `valuation: null`, never a failed overview.
     */
    @GET
    @Path("/contracts/{contractId}")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun contract(@PathParam("contractId") id: String?): Response = owned(id) { contract, party, contractId ->
        val valuation = upstream.get("${api()}/contracts/$contractId/valuation", party.toString())
            .takeIf { it.status == OK }
            ?.let { EdgeJson.parse(it)?.takeIf { node -> node.isObject } }
            ?.let(PensionProjection::valuation)
        EdgeJson.ok(PensionProjection.contract(contract) + ("valuation" to valuation))
    }

    /** The contract's priced unit transactions, newest first, from pension-service (never the fund API). */
    @GET
    @Path("/contracts/{contractId}/transactions")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun transactions(
        @PathParam("contractId") id: String?,
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
    ): Response {
        val pageNumber = page ?: 0
        val pageSize = size ?: DEFAULT_PAGE_SIZE
        if (pageNumber < 0 || pageSize !in 1..MAX_PAGE_SIZE) {
            return EdgeJson.error(BAD_REQUEST, "page must be >= 0 and size 1..$MAX_PAGE_SIZE")
        }
        return owned(id) { _, party, contractId ->
            val response = upstream.get(
                "${api()}/contracts/$contractId/transactions?page=$pageNumber&size=$pageSize",
                party.toString(),
            )
            if (response.status != OK) return@owned failure(response)
            EdgeJson.parse(response)?.takeIf { it.isObject }?.let { EdgeJson.ok(PensionProjection.transactions(it)) }
                ?: badUpstream(SERVICE)
        }
    }

    /** Pause contributions (ACTIVE -> SUSPENDED). */
    @POST
    @Path("/contracts/{contractId}/pause")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun pause(
        @PathParam("contractId") id: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response = transition(id, "suspend", key, sca)

    /** Resume contributions (SUSPENDED -> ACTIVE). */
    @POST
    @Path("/contracts/{contractId}/resume")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun resume(
        @PathParam("contractId") id: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response = transition(id, "resume", key, sca)

    /**
     * Change strategy; units switch at the next NAV. Document-bound (pension-service 1.2.0):
     * pension-service runs the suitability gate (409 REASSESSMENT_REQUIRED / WARNINGS_REQUIRED) and
     * then spends a challenge over `pension-strategy-change:<hash>`. The edge pins the effective
     * date so the hash it hands out is the one pension-service recomputes.
     */
    @PUT
    @Path("/contracts/{contractId}/strategy")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun strategy(
        @PathParam("contractId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val input = PensionChangeInput.strategy(EdgeJson.parseObject(body), LocalDate.now())
            .getOrElse { return invalid(it) }
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { _, party, contractId ->
            val linking = PensionDocuments.linking(
                "strategy-change",
                PensionDocuments.strategyChange(contractId, input),
            )!!
            val challenge = ScaConsume.challengeId(sca)
                ?: return@owned PensionDocuments.required(linking, mapOf("effectiveFrom" to input["effectiveFrom"]))
            val response = upstream.put(
                "${api()}/contracts/$contractId/strategy",
                party.toString(),
                json(input + ("scaChallengeId" to challenge.toString())),
                idempotencyKey,
                emptyMap(),
            )
            if (response.status == OK) contractOf(response, OK) else failure(response)
        }
    }

    /** The reference the customer quotes on a one-off contribution payment (S3). */
    @GET
    @Path("/contracts/{contractId}/payment-reference")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun paymentReference(@PathParam("contractId") id: String?): Response = owned(id) { _, party, contractId ->
        val response = upstream.get("${api()}/funding/contracts/$contractId/payment-reference", party.toString())
        if (response.status == OK) passThrough(response) else failure(response)
    }

    /**
     * Set up a contribution standing order or direct debit (S3 funding route). Document-bound:
     * pension-service spends a challenge over `pension-mandate-setup:<hash>` of the exact mandate
     * and resolves the debit account from the IBAN itself (it must be the customer's own active
     * account); the app never sends an account id.
     */
    @POST
    @Path("/contracts/{contractId}/contribution-mandates")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun contributionMandate(
        @PathParam("contractId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val input = PensionInput.mandate(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { _, party, contractId ->
            val linking = PensionDocuments.linking("mandate-setup", PensionDocuments.mandateSetup(contractId, input))!!
            val challenge = ScaConsume.challengeId(sca) ?: return@owned PensionDocuments.required(linking)
            val response = upstream.post(
                "${api()}/funding/contracts/$contractId/mandates",
                party.toString(),
                json(input + ("scaChallengeId" to challenge.toString())),
                idempotencyKey,
            )
            if (response.status == CREATED || response.status == OK) passThrough(response) else failure(response)
        }
    }

    /** Contributions, incentives and deductible amount for a tax year (S3 funding route). */
    @GET
    @Path("/contracts/{contractId}/tax-summary")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun taxSummary(@PathParam("contractId") id: String?, @QueryParam("year") year: String?): Response {
        val taxYear = PensionInput.taxYear(year) ?: return invalid("year must be a past or current calendar year")
        return owned(id) { _, party, contractId ->
            val response = upstream.get("${api()}/funding/contracts/$contractId/tax-years/$taxYear", party.toString())
            if (response.status == OK) passThrough(response) else failure(response)
        }
    }

    /**
     * Binding early-termination quote (S5). pension-service values the contract from the unit
     * register itself, so nothing about the value is the caller's.
     */
    @POST
    @Path("/contracts/{contractId}/termination/quote")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun terminationQuote(@PathParam("contractId") id: String?, @HeaderParam("Idempotency-Key") key: String?): Response {
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { _, party, contractId ->
            val response = upstream.post(
                "${api()}/contracts/$contractId/exit/termination/quote",
                party.toString(),
                "{}",
                idempotencyKey,
            )
            if (response.status == CREATED || response.status == OK) passThrough(response) else failure(response)
        }
    }

    @GET
    @Path("/contracts/{contractId}/termination/{noticeId}")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun terminationNotice(@PathParam("contractId") id: String?, @PathParam("noticeId") noticeId: String?): Response {
        val notice = uuid(noticeId) ?: return notFound()
        return owned(id) { _, party, contractId ->
            val response = upstream.get("${api()}/contracts/$contractId/exit/termination/$notice", party.toString())
            if (response.status == OK) passThrough(response) else failure(response)
        }
    }

    /**
     * Sign the quoted termination notice with the account it pays to. pension-service consumes the
     * challenge bound to the quote hash and the account (S8); the edge only refuses a request
     * without one.
     */
    @POST
    @Path("/contracts/{contractId}/termination/{noticeId}/sign")
    @Authorize(action = "customer.pension.contract.terminate", resource = "")
    @Blocking
    fun signTermination(
        @PathParam("contractId") id: String?,
        @PathParam("noticeId") noticeId: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response = signedExit(id, noticeId, "termination/{id}/sign", body, key, sca)

    /** Binding payout quote for a form (S5 exit route). */
    @POST
    @Path("/contracts/{contractId}/payouts")
    @Authorize(action = "customer.pension.payout.request", resource = "")
    @Blocking
    fun payout(
        @PathParam("contractId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
    ): Response {
        val input = PensionInput.payoutQuote(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { _, party, contractId ->
            val response = upstream.post(
                "${api()}/contracts/$contractId/exit/payouts/quote",
                party.toString(),
                json(input),
                idempotencyKey,
            )
            if (response.status == CREATED || response.status == OK) passThrough(response) else failure(response)
        }
    }

    @GET
    @Path("/contracts/{contractId}/payouts/{payoutId}")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun payoutStatus(@PathParam("contractId") id: String?, @PathParam("payoutId") payoutId: String?): Response {
        val payout = uuid(payoutId) ?: return notFound()
        return owned(id) { _, party, contractId ->
            val response = upstream.get("${api()}/contracts/$contractId/exit/payouts/$payout", party.toString())
            if (response.status == OK) passThrough(response) else failure(response)
        }
    }

    /** Confirm a quoted payout to the signed account (S5/S8 exit route). */
    @POST
    @Path("/contracts/{contractId}/payouts/{payoutId}/confirm")
    @Authorize(action = "customer.pension.payout.request", resource = "")
    @Blocking
    fun confirmPayout(
        @PathParam("contractId") id: String?,
        @PathParam("payoutId") payoutId: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response = signedExit(id, payoutId, "payouts/{id}/confirm", body, key, sca)

    /**
     * Move the remaining payments of a running scheduled payout to another own account (S8). The
     * change is SCA-bound and held by pension-service (notification, 3-day hold); the edge forwards
     * the challenge and never decides when the new account applies.
     */
    @PUT
    @Path("/contracts/{contractId}/payouts/{payoutId}/account")
    @Authorize(action = "customer.pension.payout.request", resource = "")
    @Blocking
    fun changePayoutAccount(
        @PathParam("contractId") id: String?,
        @PathParam("payoutId") payoutId: String?,
        body: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val input = PensionInput.payoutAccount(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val payout = uuid(payoutId) ?: return notFound()
        val challenge = ScaConsume.challengeId(sca) ?: return ScaConsume.required()
        return owned(id) { _, party, contractId ->
            val response = upstream.put(
                "${api()}/contracts/$contractId/exit/payouts/$payout/account",
                party.toString(),
                json(input + ("scaChallengeId" to challenge.toString())),
            )
            if (response.status == OK) passThrough(response) else failure(response)
        }
    }

    /**
     * A POST that pension-service signs itself (termination sign, payout confirm): the body is the
     * payout account plus the forwarded challenge; [route] names the exit sub-path with `{id}`.
     */
    @Suppress("LongParameterList")
    private fun signedExit(
        id: String?,
        subId: String?,
        route: String,
        body: String?,
        key: String?,
        sca: String?,
    ): Response {
        val input = PensionInput.payoutAccount(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val target = uuid(subId) ?: return notFound()
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        // pension-service consumes this challenge itself, bound to what it quoted; the edge
        // consuming it first would spend the single use.
        val challenge = ScaConsume.challengeId(sca) ?: return ScaConsume.required()
        return owned(id) { _, party, contractId ->
            val response = upstream.post(
                "${api()}/contracts/$contractId/exit/${route.replace("{id}", target.toString())}",
                party.toString(),
                json(input + ("scaChallengeId" to challenge.toString())),
                idempotencyKey,
            )
            if (response.status == OK) passThrough(response) else failure(response)
        }
    }

    private fun transition(id: String?, action: String, key: String?, sca: String?): Response {
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { _, party, contractId ->
            scaGate(sca, party, action, contractId, emptyMap<String, Any>())?.let { return@owned it }
            val response =
                upstream.post("${api()}/contracts/$contractId/$action", party.toString(), "{}", idempotencyKey)
            if (response.status == OK) contractOf(response, OK) else failure(response)
        }
    }

    /** Rule 2: birth date and residency from the party record, then the application upstream. */
    private fun openApplication(url: String, input: Map<String, Any?>, key: String?): Response {
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        val party = party()
        val facts = participantFacts(party) ?: return EdgeJson.error(
            UNPROCESSABLE,
            "the customer profile has no birth date or residency country",
            mapOf("code" to "PARTY_PROFILE_INCOMPLETE"),
        )
        val response = upstream.post(url, party.toString(), json(input + facts), idempotencyKey)
        return if (response.status == CREATED) passThrough(response) else failure(response)
    }

    private fun applicationStep(id: String?, step: String, input: Map<String, Any?>, key: String?): Response {
        val applicationId = uuid(id) ?: return applicationNotFound()
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return applicationResult(
            upstream.post(
                "${api()}/onboarding/applications/$applicationId/$step",
                party().toString(),
                json(input),
                idempotencyKey,
            ),
        )
    }

    private fun applicationResult(response: Response): Response = when (response.status) {
        OK -> passThrough(response)
        NOT_FOUND, FORBIDDEN -> applicationNotFound()
        else -> failure(response)
    }

    /**
     * `birthDate` and `residencyCountry` of the party, or null when either is missing. Residency is
     * the country of the registered address; only an ISO 3166 alpha-2 code is accepted.
     */
    private fun participantFacts(party: UUID): Map<String, Any?>? {
        val response = upstream.get("${partyServiceUrl.trimEnd('/')}/api/v1/parties/$party", party.toString())
        if (response.status != OK) return null
        val node = EdgeJson.parse(response)?.takeIf { it.isObject } ?: return null
        val birthDate = node.text("dateOfBirth")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val residency = node.path("address").text("countryCode")?.uppercase()?.takeIf(COUNTRY::matches) ?: return null
        return mapOf("birthDate" to birthDate.toString(), "residencyCountry" to residency)
    }

    /**
     * The SCA gate for a state-changing pension operation the edge itself authorises (rule 5). The
     * operation is bound as an APPROVAL challenge: `approvalRequestId` names the operation and the
     * contract (or application), `payloadSha256` is the hash of the exact body that will go
     * upstream. sca-service compares both strictly and consumes once. Without a challenge the
     * answer is 403 SCA_REQUIRED carrying the linking the app must have signed.
     */
    private fun scaGate(sca: String?, party: UUID, operation: String, subject: UUID, payload: Any): Response? {
        val linking = PensionScaLinking.of(operation, subject, payload)
        val challenge = ScaConsume.challengeId(sca)
            ?: return ScaConsume.required(mapOf("scaLinking" to linking + ("purpose" to "APPROVAL")))
        val consumed = ScaConsume.consume(upstream, scaServiceUrl, challenge, party.toString(), party, linking)
        return if (consumed.status == OK) null else ScaConsume.rejected()
    }

    /**
     * Runs [action] only for a contract the caller holds; see rule 3. [action] receives the
     * CANONICAL id, never the raw path segment.
     */
    private inline fun owned(id: String?, action: (JsonNode, UUID, UUID) -> Response): Response {
        val contractId = uuid(id) ?: return notFound()
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

    private fun json(value: Any) = EdgeJson.mapper.writeValueAsString(value)

    private fun contractOf(response: Response, status: Int): Response =
        EdgeJson.parse(response)?.takeIf { it.isObject }?.let { EdgeJson.ok(PensionProjection.contract(it), status) }
            ?: badUpstream(SERVICE)

    private fun passThrough(response: Response): Response =
        EdgeJson.parse(response)?.let { EdgeJson.ok(it, response.status) } ?: badUpstream(SERVICE)

    private fun failure(response: Response): Response = PensionFailure.of(response)

    private companion object {
        const val SERVICE = "pension service"
        const val CATALOG = "product catalog"
        const val MAX_OFFERINGS = 50
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 200
        const val MAX_KEY = 256
        const val OK = 200
        const val CREATED = 201
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val UNPROCESSABLE = 422
        const val BAD_GATEWAY = 502
        val COUNTRY = Regex("^[A-Z]{2}$")

        fun uuid(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        fun requiredKey(raw: String?): String? = raw?.trim()?.takeIf { it.length in 1..MAX_KEY }

        fun keyRequired() = invalid("Idempotency-Key header is required")

        fun invalid(error: Throwable) = invalid(error.message ?: "invalid request")

        fun invalid(message: String) = EdgeJson.error(BAD_REQUEST, message)

        fun notFound() = EdgeJson.error(NOT_FOUND, "pension contract not found")

        fun applicationNotFound() = EdgeJson.error(NOT_FOUND, "pension application not found")

        fun badUpstream(service: String) = EdgeJson.error(BAD_GATEWAY, "unexpected $service response")
    }
}
