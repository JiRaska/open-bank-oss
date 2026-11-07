// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.application.usecase.FraudHoldResolutionService
import com.openbank.domestic.application.workflow.FraudReviewDecision
import com.openbank.domestic.application.workflow.fraudReviewCaseKey
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Turns an analyst's decision on a `FRAUD_REVIEW` case into the held payment's workflow signal (#4403).
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
) {
    private val log = Logger.getLogger(FraudHoldResolutionConsumer::class.java)

    @Incoming(CHANNEL)
    suspend fun consume(payload: String) {
        val node = objectMapper.readTree(payload)
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

    companion object {
        const val CHANNEL = "fraud-hold-resolution-in"
        const val FRAUD_REVIEW_ALERT = "FRAUD_REVIEW"
    }
}
