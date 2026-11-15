// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.application.port.out.PaymentAdmissionMetricsPort
import com.openbank.domestic.application.usecase.FraudHoldResolutionService
import com.openbank.domestic.application.usecase.PaymentHoldResolutionService
import com.openbank.domestic.application.workflow.FraudReviewDecision
import com.openbank.domestic.application.workflow.PaymentHoldPolicy
import com.openbank.domestic.application.workflow.fraudReviewCaseKey
import com.openbank.domestic.domain.model.HoldDecision
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Turns an analyst's decision on a `FRAUD_REVIEW` case into the held payment's workflow signal (#4403),
 * and the decision on a `PAYMENT_HELD` case into the admission hold's (ADR-0374 D3). One channel and
 * one consumer group for both, because both read the same decisions off the same topic.
 *
 * Only CLEARED and BLOCKED are decisions. ESCALATED and UNDER_REVIEW keep the payment waiting, and the
 * hold then ends by its own timeout, in rejection. Every other case on the shared topic is skipped.
 *
 * `alertCode` and `transactionId` are free text from whoever creates the case, so they alone prove
 * nothing. The decision must come from the case the rail opened: its `idempotencyKey` must be exactly
 * [fraudReviewCaseKey] for the payment, and the workflow checks the same key again before acting.
 * Unparseable input and a failed signal rethrow, which the channel's dead-letter strategy parks.
 */
@ApplicationScoped
class FraudHoldResolutionConsumer(
    private val objectMapper: ObjectMapper,
    private val resolution: FraudHoldResolutionService,
    private val holdResolution: PaymentHoldResolutionService,
    private val admissionMetrics: PaymentAdmissionMetricsPort,
) {
    private val log = Logger.getLogger(FraudHoldResolutionConsumer::class.java)

    @Incoming(CHANNEL)
    suspend fun consume(payload: String) {
        val node = objectMapper.readTree(payload)
        if (node.path("alertCode").asText() == PaymentHoldPolicy.CASE_ALERT_CODE) return consumePaymentHold(node)
        if (node.path("alertCode").asText() != FRAUD_REVIEW_ALERT) return
        val decision = when (node.path("newStatus").asText()) {
            "CLEARED" -> FraudReviewDecision.RELEASE
            "BLOCKED" -> FraudReviewDecision.REJECT
            else -> return
        }
        val transactionId = node.path("transactionId").asText("")
        if (transactionId.isBlank()) {
            log.warnf("FRAUD_REVIEW case %s decided without a transaction id", node.path("caseId").asText())
            return
        }
        val paymentId = UUID.fromString(transactionId)
        val caseKey = node.path("idempotencyKey").asText("")
        if (caseKey != fraudReviewCaseKey(paymentId)) {
            log.warnf(
                "FRAUD_REVIEW case %s is not the rail's case for payment %s; ignored",
                node.path("caseId").asText(),
                paymentId,
            )
            return
        }
        EventRetry.withRetry(
            log = log,
            what = "fraud hold resolution",
            key = paymentId,
            isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
        ) {
            // The Temporal client blocks, and this handler runs on the event loop.
            withContext(Dispatchers.IO) { resolution.resolve(paymentId, decision, caseKey) }
        }
    }

    /**
     * ADR-0374 D3: the decision on the case of an admission hold. Same rules as the fraud hold it
     * generalises: only CLEARED and BLOCKED decide, and only from the case the hold itself opened
     * ([PaymentHoldPolicy.caseKey]); alert code and transaction id are free text any case creator
     * may set.
     */
    private suspend fun consumePaymentHold(node: JsonNode) {
        val decision = when (node.path("newStatus").asText()) {
            "CLEARED" -> HoldDecision.RELEASE
            "BLOCKED" -> HoldDecision.REJECT
            else -> return
        }
        val paymentId = runCatching { UUID.fromString(node.path("transactionId").asText("")) }.getOrNull()
        if (paymentId == null) {
            log.warnf("PAYMENT_HELD case %s decided without a payment id", node.path("caseId").asText())
            return
        }
        // Provenance, fail closed: only a case the hold intake opened may decide a hold, and only
        // aml-service can say which case that is. It says so with `origin` on the event; an event
        // without it (any other case, or an aml-service that predates the field) decides nothing,
        // whatever its key, so no deploy order lets a case created by hand release a payment.
        if (node.path(ORIGIN_FIELD).asText("") != HOLD_INTAKE_ORIGIN) {
            admissionMetrics.recordUnprovenHoldDecision()
            log.warnf(
                "PAYMENT_HELD case %s for payment %s does not prove it was opened by the hold intake; ignored",
                node.path("caseId").asText(),
                paymentId,
            )
            return
        }
        // The key is handed on as it arrived. Whether it is the key of this hold's case is decided
        // against the payment's row by exact equality, never by parsing it here.
        val caseKey = node.path("idempotencyKey").asText("")
        EventRetry.withRetry(
            log = log,
            what = "payment hold resolution",
            key = paymentId,
            isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
        ) {
            withContext(Dispatchers.IO) { holdResolution.resolve(paymentId, caseKey, decision) }
        }
    }

    companion object {
        const val CHANNEL = "fraud-hold-resolution-in"
        const val FRAUD_REVIEW_ALERT = "FRAUD_REVIEW"
        const val ORIGIN_FIELD = "origin"
        const val HOLD_INTAKE_ORIGIN = "PAYMENT_HOLD_INTAKE"
    }
}
