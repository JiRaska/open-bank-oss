// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.application.port.out.BusinessSigningPort
import com.openbank.customeredge.domain.model.CustomerIdentity
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

/** The payment rails a business payment can be held for, and released to. Closed: the release never takes a URL from data. */
enum class PaymentRail { DOMESTIC, SEPA, SEPA_INSTANT, SWIFT }

/** What a payment route hands the hold: the exact rail request plus the fields a signer is shown. */
data class HeldPayment(
    val rail: PaymentRail,
    val amount: String,
    val currency: String,
    /** The creditor exactly as the initiator's device signed it (dynamic linking). */
    val creditor: String?,
    val creditorName: String?,
    val reference: String?,
    val debtorAccountId: String,
    /** The body the rail would have received now. Frozen; posted verbatim on release. */
    val railRequest: String,
)

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
) {

    /** Off until delegation-service serves the signing API in the environment (deploy order), see application.yaml. */
    @ConfigProperty(name = "openbank.edge.business-approvals.enforce", defaultValue = "false")
    var enforce: Boolean = false

    @ConfigProperty(name = "openbank.edge.domestic-payment-service-url")
    lateinit var domesticPaymentServiceUrl: String

    @ConfigProperty(name = "openbank.edge.sepa-payment-service-url")
    lateinit var sepaPaymentServiceUrl: String

    @ConfigProperty(name = "openbank.edge.sepa-instant-service-url")
    lateinit var sepaInstantServiceUrl: String

    @ConfigProperty(name = "openbank.edge.swift-service-url")
    lateinit var swiftServiceUrl: String

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

        scaGate()?.let { return it }
        val challenge = UUID.fromString(requireNotNull(scaChallengeId).trim())
        val created = signing.createApproval(entity, createBody(customer.human, challenge, payment))
        if (!created.ok) {
            audit.emit(
                eventType = "CUSTOMER_PAYMENT_REFUSED",
                partyId = entity.toString(),
                operation = "payments.approval.create",
                result = "FAILURE",
                details = mapOf("reason" to "APPROVAL_NOT_CREATED", "upstreamStatus" to created.status.toString()),
            )
            return unavailable("approval request could not be created; the initiator's authentication was used")
        }
        val node = read(created.body) ?: return unavailable("approval request answer was unreadable")
        val approvalId = node.path("id").asText()
        audit.emit(
            eventType = "CUSTOMER_PAYMENT_HELD",
            partyId = entity.toString(),
            operation = "payments.${payment.rail.name.lowercase()}",
            result = "SUCCESS",
            resourceId = approvalId,
            details = mapOf(
                "initiatorPartyId" to customer.human.toString(),
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

    private fun evaluate(entity: UUID, payment: HeldPayment): JsonNode? {
        val body = objectMapper.writeValueAsString(
            mapOf(
                "amount" to payment.amount,
                "currency" to payment.currency,
                "creditorIban" to payment.creditor,
                "rail" to payment.rail.name,
            ),
        )
        val reply = signing.evaluate(entity, body)
        return if (reply.ok) read(reply.body) else null
    }

    private fun createBody(human: UUID, challenge: UUID, p: HeldPayment): String {
        val payload = objectMapper.createObjectNode().apply {
            put("rail", p.rail.name)
            put("amount", p.amount)
            put("currency", p.currency)
            p.creditor?.let { put("creditorIban", it) }
            p.creditorName?.let { put("creditorName", it) }
            p.reference?.let { put("reference", it) }
            put("debtorAccountId", p.debtorAccountId)
            set<JsonNode>("railRequest", objectMapper.readTree(p.railRequest))
        }
        val body = objectMapper.createObjectNode().apply {
            put("kind", "PAYMENT")
            set<JsonNode>("payload", payload)
            putObject("initiatorSignature").apply {
                put("partyId", human.toString())
                put("scaChallengeId", challenge.toString())
            }
        }
        return objectMapper.writeValueAsString(body)
    }

    /** Release after the last signature. Returns the release outcome for the sign response. */
    fun release(entity: UUID, approvalId: UUID): Map<String, Any?> {
        val claim = signing.releaseClaim(entity, approvalId)
        if (!claim.ok) {
            // 409: somebody else holds the claim — the payment is being (or was) released by them.
            return mapOf("status" to "RELEASE_NOT_CLAIMED", "upstreamStatus" to claim.status)
        }
        val node = read(claim.body)
        val payload = node?.path("payload")
        val rail = payload?.path("rail")?.asText()?.let { runCatching { PaymentRail.valueOf(it) }.getOrNull() }
        val railRequest = payload?.path("railRequest")?.takeIf { it.isObject }
        if (rail == null || railRequest == null) {
            report(entity, approvalId, ok = false, ref = null, error = "frozen payload has no rail request")
            return mapOf("status" to "RELEASE_FAILED", "error" to "frozen payload has no rail request")
        }
        val resp = upstream.post(
            railUrl(rail),
            entity.toString(),
            objectMapper.writeValueAsString(railRequest),
            approvalId.toString(),
        )
        val respBody = (resp.entity as? String).orEmpty()
        val ok = resp.statusInfo.family == Response.Status.Family.SUCCESSFUL
        val ref = read(respBody)?.path("id")?.textOrNull()
        val error = if (ok) null else "rail answered ${resp.status}: ${respBody.take(ERROR_MAX_CHARS)}"
        report(entity, approvalId, ok, ref, error)
        audit.emit(
            eventType = if (ok) "CUSTOMER_PAYMENT_RELEASED" else "CUSTOMER_PAYMENT_RELEASE_FAILED",
            partyId = entity.toString(),
            operation = "payments.${rail.name.lowercase()}",
            result = if (ok) "SUCCESS" else "FAILURE",
            resourceId = approvalId.toString(),
            details = mapOf("railStatus" to resp.status.toString(), "paymentId" to ref),
        )
        return linkedMapOf(
            "status" to if (ok) "RELEASED" else "RELEASE_FAILED",
            "railStatus" to resp.status,
            "paymentId" to ref,
            "error" to error,
        )
    }

    private fun report(entity: UUID, approvalId: UUID, ok: Boolean, ref: String?, error: String?) {
        val body = objectMapper.writeValueAsString(
            mapOf("ok" to ok, "releaseRef" to ref, "error" to error).filterValues { it != null },
        )
        signing.releaseResult(entity, approvalId, body)
    }

    fun railUrl(rail: PaymentRail): String = when (rail) {
        PaymentRail.DOMESTIC -> "$domesticPaymentServiceUrl/api/v1/domestic-payments"
        PaymentRail.SEPA -> "$sepaPaymentServiceUrl/api/v1/sepa-payments"
        PaymentRail.SEPA_INSTANT -> "$sepaInstantServiceUrl/api/v1/sepa-instant"
        PaymentRail.SWIFT -> "$swiftServiceUrl/api/v1/swift"
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
        const val ERROR_MAX_CHARS = 300
    }
}
