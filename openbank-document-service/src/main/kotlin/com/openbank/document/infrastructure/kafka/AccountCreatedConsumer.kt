// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.`in`.IssueOnboardingDocumentCommand
import com.openbank.document.application.port.`in`.OnboardingDocumentUseCase
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Event-driven onboarding-document issuance (ADR-0162 D7, mirrors `balance-service`'s
 * `BalanceInitConsumer` for the same `account.created` topic): consuming the event, rather than
 * `account-service` calling this service synchronously, keeps document-service off the money-path
 * account-opening gate entirely (ADR-0086) — a slow/unreachable document-service can never delay
 * or fail an account opening.
 *
 * Poison-pill safe (an unparseable payload is logged and skipped, never crashes the consumer) and
 * idempotent (delegated to [OnboardingDocumentUseCase], which no-ops on replay).
 */
@ApplicationScoped
class AccountCreatedConsumer(
    private val onboardingUseCase: OnboardingDocumentUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(AccountCreatedConsumer::class.java)

    // TooGenericExceptionCaught: deliberate -- any parse failure on an untrusted event payload must
    // be logged and skipped, never crash this consumer (poison-pill safety), regardless of the
    // specific exception Jackson happens to throw for a given malformed input.
    @Suppress("TooGenericExceptionCaught")
    @Incoming("account-created-in")
    suspend fun consume(payload: String) {
        val node: JsonNode = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "Unparseable account.created event, skipping: %s", payload.take(PAYLOAD_LOG_CHARS))
            return
        }
        if (node["eventType"]?.asText() != EVENT_TYPE) return

        val accountId = node["aggregateId"]?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val partyId = node["partyId"]?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val productId = node["productId"]?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (accountId == null || partyId == null || productId == null) {
            log.warnf(
                "AccountCreated missing accountId/partyId/productId, skipping: %s",
                payload.take(PAYLOAD_LOG_CHARS),
            )
            return
        }

        try {
            onboardingUseCase.issueOnboardingDocument(
                IssueOnboardingDocumentCommand(
                    accountId = accountId,
                    partyRef = partyId.toString(),
                    productId = productId,
                ),
            )
        } catch (e: Exception) {
            // Poison-pill safety: onboarding is best-effort and off the money path (ADR-0086), so a
            // deterministic downstream failure for ONE account (e.g. a documentTemplateCode with no
            // PUBLISHED template) must not throw out of the stream and, under smallrye-kafka's default
            // fail-strategy, wedge onboarding for EVERY subsequent account. Log and ack; a missed
            // onboarding document is re-triggerable, not a money error. This deliberately trades
            // at-least-once retry on a transient error for consumer liveness — a bounded retry / DLQ
            // is a possible follow-up if transient-loss ever proves material.
            log.errorf(e, "Onboarding-document issuance failed for account %s; skipping (event acked).", accountId)
        }
    }

    private companion object {
        const val EVENT_TYPE = "AccountCreated"
        const val PAYLOAD_LOG_CHARS = 200
    }
}
