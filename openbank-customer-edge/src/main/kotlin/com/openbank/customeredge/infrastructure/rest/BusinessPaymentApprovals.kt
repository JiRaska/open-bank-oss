// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.customeredge.application.port.out.BusinessSigningPort
import com.openbank.customeredge.application.port.out.SigningReply
import com.openbank.customeredge.domain.model.CustomerIdentity
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

/**
 * The upstreams a business instruction can be held for, and released to. Closed: the release never
 * takes a URL from data. The four payment rails, plus the two recurring-outflow services (#10281).
 */
enum class PaymentRail(val kind: String) {
    DOMESTIC("PAYMENT"),
    SEPA("PAYMENT"),
    SEPA_INSTANT("PAYMENT"),
    SWIFT("PAYMENT"),
    STANDING_ORDER("STANDING_ORDER"),
    SDD_MANDATE("SDD_MANDATE"),
}

/**
 * What a route hands the hold: the exact upstream request plus the fields a signer is shown.
 * [amount]/[currency] are null only for an SDD mandate, which has no amount (sdd-service stores no
 * maximum) and is therefore evaluated against the strictest rule (ADR-0312 addendum).
 */
data class HeldPayment(
    val rail: PaymentRail,
    val amount: String?,
    val currency: String?,
    /** The creditor exactly as the initiator's device signed it (dynamic linking). */
    val creditor: String?,
    val creditorName: String?,
    val reference: String?,
    val debtorAccountId: String,
    /** The body the rail would have received now. Frozen; posted verbatim on release. */
    val railRequest: String,
    /** Kind-specific fields a signer is shown: frequency, creditorIdentifier, mandateReference. */
    val extras: Map<String, String?> = emptyMap(),
)

internal data class DomesticApprovalReceipt(val approvalId: UUID, val status: String, val paymentId: UUID?)

internal fun canonicalPaymentId(raw: String?): UUID? = raw?.let { value ->
    runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString().equals(value, ignoreCase = true) }
}

internal data class DomesticApprovalSubmission(val keyDigest: String, val correlation: String)

/**
 * Multi-signature business payments at the edge (#10281 item 2).
 *
 * ## Hold
 *
 * A payment made under `X-Acting-For` is first [evaluate]d by delegation-service against the
 * entity's signing policy. `required == 1` returns null and the route continues EXACTLY as before
 * (its own SCA gate, its own rail call) — that path must not change by a byte. `required > 1`
 * consumes the initiator's SCA (bound to amount, currency and creditor, as today), creates an
 * approval request carrying the frozen rail request and the initiator's signature, and answers
 * `202 PENDING_APPROVAL`. The rail is not called. The initiator's challenge is consumed HERE, once;
 * delegation-service only verifies it is a consumed payment approval of that party. A co-signer's
 * challenge is the opposite: delegation-service consumes it, the edge never does (#10315).
 *
 * ## Release
 *
 * When the last signature completes the request, [release] takes a single-use release claim from
 * delegation-service, posts the frozen request to the rail named in it with
 * `Idempotency-Key = approvalId`, and reports the result. A second claim is refused upstream (409),
 * so a request can reach the rail once. The rail's own validation (mandate, balance, screening) is
 * the final check; its refusal is reported as RELEASE_FAILED.
 *
 * ## Fail-closed
 *
 * With enforcement on, an entity payment whose policy cannot be evaluated is refused (503) rather
 * than sent with one signature: a JOINT mandate would otherwise be bypassed by an outage.
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
class BusinessPaymentApprovals(
    private val signing: BusinessSigningPort,
    private val upstream: UpstreamClient,
    private val objectMapper: ObjectMapper,
    private val audit: EdgeAuditPublisher,
    /**
     * Off until delegation-service serves the signing API in the environment (deploy order), see
     * application.yaml. A constructor parameter WITHOUT a Kotlin default: a default would make Arc
     * build the bean through the synthetic constructor and never apply the config.
     */
    @ConfigProperty(name = "openbank.edge.business-approvals.enforce", defaultValue = "false")
    private val enforce: Boolean,
) {

    @ConfigProperty(name = "openbank.edge.domestic-payment-service-url")
    lateinit var domesticPaymentServiceUrl: String

    @ConfigProperty(name = "openbank.edge.sepa-payment-service-url")
    lateinit var sepaPaymentServiceUrl: String

    @ConfigProperty(name = "openbank.edge.sepa-instant-service-url")
    lateinit var sepaInstantServiceUrl: String

    @ConfigProperty(name = "openbank.edge.swift-service-url")
    lateinit var swiftServiceUrl: String

    @ConfigProperty(name = "openbank.edge.standing-order-service-url")
    lateinit var standingOrderServiceUrl: String

    @ConfigProperty(name = "openbank.edge.sdd-service-url")
    lateinit var sddServiceUrl: String

    @ConfigProperty(name = "openbank.edge.sca-service-url")
    lateinit var scaServiceUrl: String

    /**
     * Null: not held — the caller continues on its unchanged single-signature path. Otherwise the
     * response to return (202 held, 503 cannot evaluate, or the SCA gate's own refusal).
     *
     * [scaGate] is the route's own settlement gate, run only on the held path, so the initiator's
     * challenge is consumed exactly as a single-signature payment would consume it.
     */
    fun hold(
        customer: CustomerIdentity,
        payment: HeldPayment,
        scaChallengeId: String?,
        scaGate: () -> Response?,
    ): Response? {
        val entity = customer.actingFor ?: return null
        if (!enforce) return null
        val evaluation = evaluate(entity, payment) ?: return unavailable("signing policy could not be evaluated")
        val required = evaluation.path("required").asInt(0)
        if (required < 1) return unavailable("signing policy answered no signer count")
        if (required == 1) return null

        return createHeld(customer, payment, scaChallengeId, required, null, scaGate)
    }

    /**
     * A successful DIRECT claim is the only answer that lets the caller dispatch to the rail.
     * HELD stays held even when a subsequent policy read would normally select direct dispatch.
     */
    internal fun routeDomestic(
        customer: CustomerIdentity,
        payment: HeldPayment,
        scaChallengeId: String?,
        submission: DomesticApprovalSubmission,
        scaGate: () -> Response?,
    ): Response? {
        val entity = checkNotNull(customer.actingFor)
        val payload = frozenPayload(payment)
        val claimBody = objectMapper.createObjectNode().apply {
            put("initiatorPartyId", customer.human.toString())
            put("enforceApprovalPolicy", enforce)
            putSubmission(submission)
            set<JsonNode>("payload", payload)
        }
        val claim = signing.claimDomesticSubmission(entity, claimBody.toString())
        if (claim.status != Response.Status.OK.statusCode) {
            return submissionConflict(claim) ?: unavailable("domestic submission could not be bound")
        }
        return when (read(claim.body)?.path("mode")?.textOrNull()) {
            "DIRECT" -> null
            "HELD" -> {
                if (!enforce) return conflict(DOMESTIC_HELD_POLICY_CHANGED, "The payment must stay in approval")
                val policyPayment = payment.copy(currency = payment.currency?.trim()?.uppercase())
                val required = evaluate(entity, policyPayment)?.path("required")?.asInt(0) ?: 0
                when {
                    required < 1 -> unavailable("signing policy could not be evaluated")
                    required == 1 -> conflict(DOMESTIC_HELD_POLICY_CHANGED, "The payment must stay in approval")
                    else -> createHeld(customer, payment, scaChallengeId, required, submission, scaGate)
                }
            }
            else -> unavailable("domestic submission mode was unreadable")
        }
    }

    private fun createHeld(
        customer: CustomerIdentity,
        payment: HeldPayment,
        scaChallengeId: String?,
        required: Int,
        submission: DomesticApprovalSubmission?,
        scaGate: () -> Response?,
    ): Response {
        val entity = checkNotNull(customer.actingFor)
        scaGate()?.let { return it }
        val challenge = UUID.fromString(requireNotNull(scaChallengeId).trim())
        val body = createBody(customer.human, challenge, payment, submission)
        val created = signing.createApproval(entity, body)
        if (!created.ok) {
            audit.emit(
                eventType = "CUSTOMER_PAYMENT_REFUSED",
                partyId = entity.toString(),
                operation = "payments.approval.create",
                result = "FAILURE",
                details = mapOf("reason" to "APPROVAL_NOT_CREATED", "upstreamStatus" to created.status.toString()),
            )
            submissionConflict(created)?.let { return it }
            return unavailable("approval request could not be created; the initiator's authentication was used")
        }
        val node = read(created.body) ?: return unavailable("approval request answer was unreadable")
        val approvalId = node.path("id").asText()
        audit.emit(
            eventType = "CUSTOMER_PAYMENT_HELD",
            partyId = entity.toString(),
            operation = operationOf(payment.rail),
            result = "SUCCESS",
            resourceId = approvalId,
            details = mapOf(
                "initiatorPartyId" to customer.human.toString(),
                "kind" to payment.rail.kind,
                "required" to required.toString(),
                "amount" to payment.amount,
                "currency" to payment.currency,
            ),
        )
        val out = linkedMapOf(
            "approvalId" to approvalId,
            "status" to "PENDING_APPROVAL",
            "payloadSha256" to node.path("payloadSha256").textOrNull(),
            "required" to (node.path("required").takeIf { it.isInt }?.asInt() ?: required),
            "collected" to 1,
            "expiresAt" to node.path("expiresAt").textOrNull(),
        )
        return Response.status(Response.Status.ACCEPTED).entity(out).type(MediaType.APPLICATION_JSON).build()
    }

    internal fun lookupDomesticSubmission(
        entity: UUID,
        initiator: UUID,
        debtorAccountId: UUID,
        submission: DomesticApprovalSubmission,
    ): DomesticApprovalReceipt? {
        val body = objectMapper.createObjectNode().apply {
            put("initiatorPartyId", initiator.toString())
            put("debtorAccountId", debtorAccountId.toString())
            putSubmission(submission)
        }
        val reply = signing.lookupDomesticApproval(entity, body.toString())
        if (reply.status != Response.Status.OK.statusCode) return null
        val receipt = read(reply.body) ?: return null
        val id = receipt.path("approvalId").textOrNull()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        val status = receipt.path("status").textOrNull()?.takeIf(DOMESTIC_APPROVAL_STATUSES::contains) ?: return null
        val paymentId = canonicalPaymentId(receipt.path("releaseRef").textOrNull()).takeIf { status == "RELEASED" }
        return DomesticApprovalReceipt(id, status, paymentId)
    }

    private fun evaluate(entity: UUID, payment: HeldPayment): JsonNode? {
        val body = objectMapper.writeValueAsString(
            mapOf(
                "kind" to payment.rail.kind,
                "amount" to payment.amount,
                "currency" to payment.currency,
                "creditorIban" to payment.creditor.takeUnless { payment.rail == PaymentRail.SDD_MANDATE },
                "rail" to payment.rail.name,
            ).filterValues { it != null },
        )
        val reply = signing.evaluate(entity, body)
        return if (reply.ok) read(reply.body) else null
    }

    private fun frozenPayload(p: HeldPayment): JsonNode = objectMapper.createObjectNode().apply {
        put("rail", p.rail.name)
        p.amount?.let { put("amount", it) }
        p.currency?.let { put("currency", it) }
        if (p.rail != PaymentRail.SDD_MANDATE) p.creditor?.let { put("creditorIban", it) }
        p.extras.forEach { (k, v) -> v?.let { put(k, it) } }
        p.creditorName?.let { put("creditorName", it) }
        p.reference?.let { put("reference", it) }
        put("debtorAccountId", p.debtorAccountId)
        set<JsonNode>("railRequest", objectMapper.readTree(p.railRequest))
    }

    private fun createBody(
        human: UUID,
        challenge: UUID,
        p: HeldPayment,
        submission: DomesticApprovalSubmission?,
    ): String = objectMapper.createObjectNode().apply {
        put("kind", p.rail.kind)
        submission?.let { putSubmission(it) }
        set<JsonNode>("payload", frozenPayload(p))
        putObject("initiatorSignature").apply {
            put("partyId", human.toString())
            put("scaChallengeId", challenge.toString())
        }
    }.toString()

    private fun ObjectNode.putSubmission(submission: DomesticApprovalSubmission) {
        put("submissionKeyDigest", submission.keyDigest)
        put("submissionCorrelation", submission.correlation)
    }

    private fun submissionConflict(reply: SigningReply): Response? {
        if (reply.status != Response.Status.CONFLICT.statusCode) return null
        return when (val code = read(reply.body)?.path("code")?.textOrNull()) {
            SUBMISSION_CORRELATION_REUSED -> conflict(code, "Submission key already used")
            DOMESTIC_DIRECT_NO_LONGER_AUTHORIZED -> conflict(code, "The original payment requires review")
            DOMESTIC_HELD_POLICY_CHANGED -> conflict(code, "The payment must stay in approval")
            else -> null
        }
    }

    private fun conflict(code: String, message: String): Response = Response.status(Response.Status.CONFLICT)
        .entity(mapOf("code" to code, "message" to message))
        .type(MediaType.APPLICATION_JSON)
        .build()

    /** Release after the last signature. An ambiguous domestic dispatch has no sign response outcome. */
    fun release(entity: UUID, approvalId: UUID, domestic: Boolean): Map<String, Any?>? {
        val claim = signing.releaseClaim(entity, approvalId, domestic)
        if (!claim.ok) {
            // 409: somebody else holds the claim — the payment is being (or was) released by them.
            return mapOf("status" to "RELEASE_NOT_CLAIMED", "upstreamStatus" to claim.status)
        }
        val node = read(claim.body)
        val payload = node?.path("payload")
        val rail = payload?.path("rail")?.asText()?.let { runCatching { PaymentRail.valueOf(it) }.getOrNull() }
        val railRequest = payload?.path("railRequest")?.takeIf { it.isObject }
        if (rail == null || railRequest == null) {
            return invalidRelease(entity, approvalId, "frozen payload has no rail request")
        }
        if (domestic != (rail == PaymentRail.DOMESTIC)) {
            return invalidRelease(entity, approvalId, "frozen rail does not match the release route")
        }
        val originalKey = node?.get("originalSubmissionKeyDigest")
        val digest = originalKey?.textOrNull()?.takeIf(ORIGINAL_KEY_DIGEST_PATTERN::matches)
        if (originalKey != null && (rail != PaymentRail.DOMESTIC || digest == null)) {
            return invalidRelease(entity, approvalId, "invalid original submission digest")
        }
        val resp = dispatchRelease(rail, entity, approvalId, railRequest, digest)
        return recordReleaseOutcome(entity, approvalId, rail, resp, modeBound = digest != null)
    }

    private fun recordReleaseOutcome(
        entity: UUID,
        approvalId: UUID,
        rail: PaymentRail,
        response: Response,
        modeBound: Boolean,
    ): Map<String, Any?>? {
        val body = (response.entity as? String).orEmpty()
        val result = read(body)
        val ok = response.statusInfo.family == Response.Status.Family.SUCCESSFUL
        val ref = result?.path("id")?.textOrNull().takeIf { !modeBound || ok }
        val refusal = if (modeBound) definiteDomesticRefusal(response.status, result) else null
        val accepted = response.status == Response.Status.CREATED.statusCode && canonicalPaymentId(ref) != null
        if (modeBound && refusal == null && !accepted) {
            // No rail evidence. Preserve the winning claim and let the original-key lookup reconcile it.
            return null
        }
        val error = if (ok) null else "rail answered ${response.status}: ${refusal ?: body.take(ERROR_MAX_CHARS)}"
        report(entity, approvalId, ok, ref, error)
        val operation = operationOf(rail)
        val details = mapOf("railStatus" to response.status.toString(), "paymentId" to ref)
        if (ok) {
            audit.emit(
                eventType = "CUSTOMER_PAYMENT_RELEASED",
                partyId = entity.toString(),
                operation = operation,
                result = "SUCCESS",
                resourceId = approvalId.toString(),
                details = details,
            )
        } else {
            audit.emit(
                eventType = "CUSTOMER_PAYMENT_RELEASE_FAILED",
                partyId = entity.toString(),
                operation = operation,
                result = "FAILURE",
                resourceId = approvalId.toString(),
                details = details,
            )
        }
        return linkedMapOf(
            "status" to if (ok) "RELEASED" else "RELEASE_FAILED",
            "railStatus" to response.status,
            "paymentId" to ref,
            "error" to error,
        )
    }

    private fun definiteDomesticRefusal(status: Int, result: JsonNode?): String? {
        val code = result?.path("code")?.textOrNull() ?: return null
        val amountViolation = result.path("violations").any { it.path("field").asText() == "amount" }
        val amountValidation = code == "VALIDATION_ERROR" && amountViolation
        // These typed validation and authorization errors are produced before binding a rail key.
        return when (status) {
            Response.Status.BAD_REQUEST.statusCode ->
                code.takeIf { it in DOMESTIC_PRE_DISPATCH_VALIDATION || amountValidation }

            Response.Status.FORBIDDEN.statusCode -> code.takeIf { it == "FORBIDDEN" }

            else -> null
        }
    }

    private fun dispatchRelease(
        rail: PaymentRail,
        entity: UUID,
        approvalId: UUID,
        railRequest: JsonNode,
        digest: String?,
    ): Response {
        val body = objectMapper.writeValueAsString(railRequest)
        return if (digest == null) {
            upstream.post(railUrl(rail), entity.toString(), body, approvalId.toString())
        } else {
            upstream.post(
                "${railUrl(rail)}/approval-releases",
                entity.toString(),
                body,
                approvalId.toString(),
                mapOf(ORIGINAL_KEY_DIGEST_HEADER to digest),
            )
        }
    }

    private fun invalidRelease(entity: UUID, approvalId: UUID, error: String): Map<String, String> {
        report(entity, approvalId, ok = false, ref = null, error = error)
        return mapOf("status" to "RELEASE_FAILED", "error" to error)
    }

    private fun report(entity: UUID, approvalId: UUID, ok: Boolean, ref: String?, error: String?) {
        val body = objectMapper.writeValueAsString(
            mapOf("ok" to ok, "releaseRef" to ref, "error" to error).filterValues { it != null },
        )
        signing.releaseResult(entity, approvalId, body)
    }

    private fun operationOf(rail: PaymentRail): String = when (rail) {
        PaymentRail.STANDING_ORDER -> "standingOrders.create"
        PaymentRail.SDD_MANDATE -> "sdd.mandates.create"
        else -> "payments.${rail.name.lowercase()}"
    }

    fun railUrl(rail: PaymentRail): String = when (rail) {
        PaymentRail.DOMESTIC -> "$domesticPaymentServiceUrl/api/v1/domestic-payments"
        PaymentRail.SEPA -> "$sepaPaymentServiceUrl/api/v1/sepa-payments"
        PaymentRail.SEPA_INSTANT -> "$sepaInstantServiceUrl/api/v1/sepa-instant"
        PaymentRail.SWIFT -> "$swiftServiceUrl/api/v1/swift"
        PaymentRail.STANDING_ORDER -> "$standingOrderServiceUrl/api/v1/standing-orders"
        PaymentRail.SDD_MANDATE -> "$sddServiceUrl/api/v1/sdd/mandates"
    }

    private fun read(body: String): JsonNode? = runCatching { objectMapper.readTree(body) }.getOrNull()
        ?.takeIf { it.isObject }

    private fun unavailable(message: String) =
        refusal(Response.Status.SERVICE_UNAVAILABLE, "APPROVAL_UNAVAILABLE", message)

    private fun refusal(status: Response.Status, code: String, message: String): Response = Response.status(status)
        .entity(objectMapper.writeValueAsString(mapOf("error" to message, "code" to code)))
        .type(MediaType.APPLICATION_JSON)
        .build()

    private fun JsonNode.textOrNull(): String? = takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()

    private companion object {
        val DOMESTIC_APPROVAL_STATUSES = setOf(
            "AWAITING_INITIATOR",
            "PENDING",
            "APPROVED",
            "REJECTED",
            "EXPIRED",
            "RELEASED",
            "RELEASE_FAILED",
        )

        val DOMESTIC_PRE_DISPATCH_VALIDATION = setOf(
            "AMOUNT_SCALE_EXCEEDED",
            "CURRENCY_NOT_ALLOWED",
            "CURRENCY_UNSUPPORTED",
            "AMOUNT_NOT_POSITIVE",
        )

        const val SUBMISSION_CORRELATION_REUSED = "SUBMISSION_CORRELATION_REUSED"
        const val DOMESTIC_DIRECT_NO_LONGER_AUTHORIZED = "DOMESTIC_DIRECT_NO_LONGER_AUTHORIZED"
        const val DOMESTIC_HELD_POLICY_CHANGED = "DOMESTIC_HELD_POLICY_CHANGED"
        const val ORIGINAL_KEY_DIGEST_HEADER = "X-OpenBank-Original-Submission-Key-Digest"
        val ORIGINAL_KEY_DIGEST_PATTERN = Regex("[0-9a-f]{64}")

        const val ERROR_MAX_CHARS = 300
    }
}
