// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The pension follow-up flows of `docs/ux/pension-client-flows.md` over pension-service API 1.2.0:
 * the questionnaire (F7: question set, draft, profile, warnings), schedule and beneficiary changes
 * (F1), mandate cancellation (F3), statements and payout documents (F4), and annuity offers,
 * selection and cancellation (F6). Same rules as [CustomerPensionResource]: the participant is the
 * token's own party, every by-id contract route proves ownership first (another party's contract is
 * the same 404 as an unknown one), every POST/PUT carries the caller's `Idempotency-Key`, upstream
 * error bodies are projected through [PensionFailure], and every change is SCA-bound to the
 * document pension-service signs ([PensionDocuments]) — without a challenge the answer is 403
 * `SCA_REQUIRED` carrying the exact `pension-<op>:<hash>` linking, and nothing changes upstream.
 */
@Path("/customer/v1/pension")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
@Suppress("TooManyFunctions")
class CustomerPensionChangeResource(private val upstream: UpstreamClient, private val parties: CustomerPartyResolver) {
    @ConfigProperty(name = "openbank.edge.pension-service-url")
    lateinit var pensionServiceUrl: String

    // ---- F7 questionnaire (application-scoped; pension-service scopes applications by party) ----

    /** The question set with saved answers, progress and prefill. */
    @GET
    @Path("/applications/{applicationId}/questionnaire")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun questionSet(@PathParam("applicationId") id: String?, @QueryParam("lang") lang: String?): Response =
        application(id) { applicationId ->
            upstream.get(withLang("${app(applicationId)}/questionnaire", lang), party())
        }

    /** Save the answers so far; leaving and returning resumes at `progress.nextStep`. */
    @PUT
    @Path("/applications/{applicationId}/questionnaire/draft")
    @Authorize(action = "customer.pension.contract.create", resource = "")
    @Blocking
    fun saveDraft(
        @PathParam("applicationId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
    ): Response {
        val input = PensionChangeInput.draft(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return application(id) { applicationId ->
            upstream.put("${app(applicationId)}/questionnaire/draft", party(), json(input), idempotencyKey, emptyMap())
        }
    }

    /** Risk class, the answers that set it, horizon and the recommendation; 409 once expired. */
    @GET
    @Path("/applications/{applicationId}/profile")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun profile(@PathParam("applicationId") id: String?, @QueryParam("lang") lang: String?): Response =
        application(id) { applicationId -> upstream.get(withLang("${app(applicationId)}/profile", lang), party()) }

    /** The warnings a strategy needs acknowledged before it can be chosen. */
    @GET
    @Path("/applications/{applicationId}/warnings")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun warnings(
        @PathParam("applicationId") id: String?,
        @QueryParam("strategyCode") strategyCode: String?,
        @QueryParam("lang") lang: String?,
    ): Response {
        val code = strategyCode?.takeIf(STRATEGY::matches) ?: return invalid("strategyCode is missing or malformed")
        return application(id) { applicationId ->
            upstream.get(withLang("${app(applicationId)}/warnings?strategyCode=${enc(code)}", lang, "&"), party())
        }
    }

    /** Record that the warnings were shown and understood (the server stores the shown text's hash). */
    @POST
    @Path("/applications/{applicationId}/warnings/acknowledge")
    @Authorize(action = "customer.pension.contract.create", resource = "")
    @Blocking
    fun acknowledgeWarnings(
        @PathParam("applicationId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
    ): Response {
        val input = PensionChangeInput.acknowledge(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return application(id) { applicationId ->
            upstream.post("${app(applicationId)}/warnings/acknowledge", party(), json(input), idempotencyKey)
        }
    }

    // ---- F1 contribution schedule and beneficiaries ----

    @GET
    @Path("/contracts/{contractId}/contribution-schedule")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun schedule(@PathParam("contractId") id: String?): Response =
        owned(id) { party, contractId -> read("${api()}/contracts/$contractId/contribution-schedule", party) }

    /** What the change would do (effective date, incentive before/after) and the hash to sign. */
    @POST
    @Path("/contracts/{contractId}/contribution-schedule/preview")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun previewSchedule(
        @PathParam("contractId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
    ): Response {
        val input = PensionChangeInput.scheduleChange(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return preview(id, "contribution-schedule", input, key)
    }

    /** Apply from the next collection cycle; SCA over `pension-schedule-change:<documentSha256>`. */
    @POST
    @Path("/contracts/{contractId}/contribution-schedule/changes")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun changeSchedule(
        @PathParam("contractId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val input = PensionChangeInput.scheduleChange(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return previewedChange(id, "contribution-schedule", "schedule-change", input, key, sca)
    }

    @GET
    @Path("/contracts/{contractId}/beneficiaries")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun beneficiaries(@PathParam("contractId") id: String?): Response =
        owned(id) { party, contractId -> read("${api()}/contracts/$contractId/beneficiaries", party) }

    @POST
    @Path("/contracts/{contractId}/beneficiaries/preview")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun previewBeneficiaries(
        @PathParam("contractId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
    ): Response {
        val input = PensionChangeInput.beneficiaryChange(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return preview(id, "beneficiaries", input, key)
    }

    /** Shares total exactly 100; SCA over `pension-beneficiary-change:<documentSha256>`. */
    @POST
    @Path("/contracts/{contractId}/beneficiaries/changes")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun changeBeneficiaries(
        @PathParam("contractId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val input = PensionChangeInput.beneficiaryChange(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        return previewedChange(id, "beneficiaries", "beneficiary-change", input, key, sca)
    }

    // ---- F3 mandates ----

    /** Cancel a contribution mandate; SCA over `pension-mandate-cancellation:<hash>`. */
    @POST
    @Path("/contracts/{contractId}/contribution-mandates/{mandateId}/cancel")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun cancelMandate(
        @PathParam("contractId") id: String?,
        @PathParam("mandateId") mandateId: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val mandate = uuid(mandateId) ?: return notFound()
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { party, contractId ->
            val linking =
                PensionDocuments.linking("mandate-cancellation", PensionDocuments.mandateCancel(contractId, mandate))!!
            val challenge = ScaConsume.challengeId(sca) ?: return@owned PensionDocuments.required(linking)
            relay(
                upstream.post(
                    "${api()}/funding/contracts/$contractId/mandates/$mandate/cancel",
                    party,
                    json(mapOf("scaChallengeId" to challenge.toString())),
                    idempotencyKey,
                ),
            )
        }
    }

    // ---- F4 statements and documents ----

    /** The annual statement of a past or current year. */
    @GET
    @Path("/contracts/{contractId}/annual-statements/{year}")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun annualStatement(@PathParam("contractId") id: String?, @PathParam("year") year: String?): Response {
        val taxYear = PensionInput.taxYear(year) ?: return invalid("year must be a past or current calendar year")
        return owned(id) { party, contractId ->
            read("${api()}/contracts/$contractId/annual-statements/$taxYear", party)
        }
    }

    /** Which payout forms the pack allows today, and from when the others become available. */
    @GET
    @Path("/contracts/{contractId}/payout-eligibility")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun payoutEligibility(@PathParam("contractId") id: String?): Response =
        owned(id) { party, contractId -> read("${api()}/contracts/$contractId/exit/payout-eligibility", party) }

    /** The payout statement (what was paid, withheld and to which account). */
    @GET
    @Path("/contracts/{contractId}/payouts/{payoutId}/statement")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun payoutStatement(@PathParam("contractId") id: String?, @PathParam("payoutId") payoutId: String?): Response {
        val payout = uuid(payoutId) ?: return notFound()
        return owned(id) { party, contractId ->
            read("${api()}/contracts/$contractId/exit/payouts/$payout/statement", party)
        }
    }

    // ---- F6 annuity ----

    /** The annuity purchase of an ANNUITY payout: offers side by side, status, cooling-off. */
    @GET
    @Path("/contracts/{contractId}/payouts/{payoutId}/annuity")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun annuity(@PathParam("contractId") id: String?, @PathParam("payoutId") payoutId: String?): Response {
        val payout = uuid(payoutId) ?: return notFound()
        return owned(id) { party, contractId -> read(annuityUrl(contractId, payout), party) }
    }

    /** Ask every active partner for an offer; none is pre-selected. */
    @POST
    @Path("/contracts/{contractId}/payouts/{payoutId}/annuity/offers")
    @Authorize(action = "customer.pension.payout.request", resource = "")
    @Blocking
    fun annuityOffers(
        @PathParam("contractId") id: String?,
        @PathParam("payoutId") payoutId: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
    ): Response {
        val input = PensionChangeInput.annuityOffers(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val payout = uuid(payoutId) ?: return notFound()
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { party, contractId ->
            relay(upstream.post("${annuityUrl(contractId, payout)}/offers", party, json(input), idempotencyKey))
        }
    }

    /** Select one offer; SCA over `pension-annuity-selection:<selectionHash>` of that offer. */
    @POST
    @Path("/contracts/{contractId}/payouts/{payoutId}/annuity/selection")
    @Authorize(action = "customer.pension.payout.request", resource = "")
    @Blocking
    fun selectAnnuity(
        @PathParam("contractId") id: String?,
        @PathParam("payoutId") payoutId: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val input = PensionChangeInput.annuitySelection(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val payout = uuid(payoutId) ?: return notFound()
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { party, contractId ->
            val url = annuityUrl(contractId, payout)
            val challenge = ScaConsume.challengeId(sca) ?: return@owned purchase(url, party) { p ->
                PensionDocuments.selectionHash(p, input.getValue("partnerId"), input.getValue("offerId"))
                    ?.let { PensionDocuments.linking("annuity-selection", it) }
            }
            relay(
                upstream.post(
                    "$url/selection",
                    party,
                    json(input + ("scaChallengeId" to challenge.toString())),
                    idempotencyKey,
                ),
            )
        }
    }

    /** Cancel within the partner's cooling-off; SCA over `pension-annuity-cancellation:<cancellationHash>`. */
    @POST
    @Path("/contracts/{contractId}/payouts/{payoutId}/annuity/cancellation")
    @Authorize(action = "customer.pension.payout.request", resource = "")
    @Blocking
    fun cancelAnnuity(
        @PathParam("contractId") id: String?,
        @PathParam("payoutId") payoutId: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val payout = uuid(payoutId) ?: return notFound()
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { party, contractId ->
            val url = annuityUrl(contractId, payout)
            val challenge = ScaConsume.challengeId(sca) ?: return@owned purchase(url, party) { p ->
                PensionDocuments.linking("annuity-cancellation", p.text("cancellationHash"))
            }
            relay(
                upstream.post(
                    "$url/cancellation",
                    party,
                    json(mapOf("scaChallengeId" to challenge.toString())),
                    idempotencyKey,
                ),
            )
        }
    }

    // ---- helpers ----

    private fun preview(id: String?, resource: String, input: Map<String, Any?>, key: String?): Response {
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { party, contractId ->
            relay(upstream.post("${api()}/contracts/$contractId/$resource/preview", party, json(input), idempotencyKey))
        }
    }

    /**
     * A change pension-service signs over the `documentSha256` its preview issues. Without a
     * challenge the edge asks pension-service for that preview (which changes nothing) and answers
     * 403 SCA_REQUIRED with `pension-<operation>:<documentSha256>`; with one, the identical body
     * goes upstream with the challenge, and pension-service recomputes the document to verify it.
     */
    @Suppress("LongParameterList")
    private fun previewedChange(
        id: String?,
        resource: String,
        operation: String,
        input: Map<String, Any?>,
        key: String?,
        sca: String?,
    ): Response {
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { party, contractId ->
            val base = "${api()}/contracts/$contractId/$resource"
            val challenge = ScaConsume.challengeId(sca)
            if (challenge == null) {
                val preview = upstream.post("$base/preview", party, json(input), "$idempotencyKey:preview")
                if (preview.status != OK) return@owned PensionFailure.of(preview)
                val document = EdgeJson.parse(preview)?.takeIf { it.isObject } ?: return@owned badUpstream()
                val linking = PensionDocuments.linking(operation, document.text("documentSha256"))
                    ?: return@owned badUpstream()
                return@owned PensionDocuments.required(linking, mapOf("preview" to document))
            }
            relay(
                upstream.post(
                    "$base/changes",
                    party,
                    json(input + ("scaChallengeId" to challenge.toString())),
                    idempotencyKey,
                ),
            )
        }
    }

    /** 403 SCA_REQUIRED with the linking [hash] reads off the current annuity purchase. */
    private inline fun purchase(url: String, party: String, hash: (JsonNode) -> Map<String, String>?): Response {
        val response = upstream.get(url, party)
        if (response.status != OK) return PensionFailure.of(response)
        val purchase = EdgeJson.parse(response)?.takeIf { it.isObject } ?: return badUpstream()
        val linking = hash(purchase) ?: return EdgeJson.error(
            CONFLICT,
            "no such offer, or nothing to sign in the current annuity state",
            mapOf("code" to "INVALID_CONTRACT_STATE"),
        )
        return PensionDocuments.required(linking)
    }

    private fun read(url: String, party: String): Response = relay(upstream.get(url, party))

    private fun relay(response: Response): Response = when (response.status) {
        OK, CREATED -> EdgeJson.parse(response)?.let { EdgeJson.ok(it, response.status) } ?: badUpstream()
        else -> PensionFailure.of(response)
    }

    /** Application-scoped call: pension-service answers 404 for another party's application. */
    private inline fun application(id: String?, call: (UUID) -> Response): Response {
        val applicationId = uuid(id) ?: return applicationNotFound()
        val response = call(applicationId)
        return when (response.status) {
            NOT_FOUND, FORBIDDEN -> applicationNotFound()
            else -> relay(response)
        }
    }

    /** Rule 3 of [CustomerPensionResource]: only a contract the caller holds, by canonical id. */
    private inline fun owned(id: String?, action: (String, UUID) -> Response): Response {
        val contractId = uuid(id) ?: return notFound()
        val party = party()
        val response = upstream.get("${api()}/contracts/$contractId", party)
        if (response.status == NOT_FOUND) return notFound()
        if (response.status != OK) return EdgeJson.upstreamFailure(response, SERVICE)
        val contract = EdgeJson.parse(response)?.takeIf { it.isObject } ?: return badUpstream()
        if (contract.text("participantPartyId") != party) return notFound()
        return action(party, contractId)
    }

    private fun party(): String = parties.resolve(null).toString()

    private fun api() = "${pensionServiceUrl.trimEnd('/')}/api/v1/pension"

    private fun app(applicationId: UUID) = "${api()}/onboarding/applications/$applicationId"

    private fun annuityUrl(contractId: UUID, payout: UUID) =
        "${api()}/contracts/$contractId/exit/payouts/$payout/annuity"

    private fun json(value: Any) = EdgeJson.mapper.writeValueAsString(value)

    private companion object {
        const val SERVICE = "pension service"
        const val MAX_KEY = 256
        const val OK = 200
        const val CREATED = 201
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val BAD_GATEWAY = 502
        val STRATEGY = Regex("^[A-Z0-9_]{1,64}$")

        fun withLang(url: String, lang: String?, separator: String = "?"): String =
            PensionChangeInput.lang(lang)?.let { "$url${separator}lang=$it" } ?: url

        fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        fun uuid(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        fun requiredKey(raw: String?): String? = raw?.trim()?.takeIf { it.length in 1..MAX_KEY }

        fun keyRequired() = invalid("Idempotency-Key header is required")

        fun invalid(error: Throwable) = invalid(error.message ?: "invalid request")

        fun invalid(message: String) = EdgeJson.error(BAD_REQUEST, message)

        fun notFound() = EdgeJson.error(NOT_FOUND, "pension contract not found")

        fun applicationNotFound() = EdgeJson.error(NOT_FOUND, "pension application not found")

        fun badUpstream() = EdgeJson.error(BAD_GATEWAY, "unexpected $SERVICE response")
    }
}
