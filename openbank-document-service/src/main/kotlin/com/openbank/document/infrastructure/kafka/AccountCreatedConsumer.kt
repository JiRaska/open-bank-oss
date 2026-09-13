// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.`in`.IssueOnboardingDocumentCommand
import com.openbank.document.application.port.`in`.OnboardingDocumentUseCase
import com.openbank.libs.messaging.EventRetry
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
 *
 * Issuance failures are split by kind (#5698) — see [withBoundedRetry]. A deterministic failure for
 * one account is still acked, exactly as ADR-0086's off-the-money-path trade-off intends; a
 * transient one is retried and then rethrown, so it dead-letters instead of vanishing.
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
            EventRetry.withRetry(
                log,
                "Onboarding-document issuance for account $accountId",
                null,
                isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
            ) {
                onboardingUseCase.issueOnboardingDocument(
                    IssueOnboardingDocumentCommand(
                        accountId = accountId,
                        partyRef = partyId.toString(),
                        productId = productId,
                    ),
                )
            }
        } catch (e: IllegalStateException) {
            ackDeterministic(e, accountId)
        } catch (e: IllegalArgumentException) {
            ackDeterministic(e, accountId)
        }
        // Anything else has already been retried and is deliberately NOT caught: it propagates so
        // smallrye-kafka can dead-letter the record. Swallowing it acked an event that did no work,
        // which is indistinguishable from success on every dashboard there is (#5698).
    }

    /**
     * Ack a failure that is a property of THIS account rather than of the infrastructure — e.g. a
     * product whose `documentTemplateCode` has no PUBLISHED template. Onboarding is best-effort and
     * off the money path (ADR-0086), so such an event must not throw out of the stream and, under
     * smallrye-kafka's default fail-strategy, wedge onboarding for EVERY subsequent account. It also
     * cannot be fixed by a retry or by a DLQ: the next delivery fails identically. A missed
     * onboarding document is re-triggerable, not a money error.
     */
    private fun ackDeterministic(e: RuntimeException, accountId: UUID) {
        log.errorf(
            e,
            "Onboarding-document issuance failed deterministically for account %s; skipping (event acked).",
            accountId,
        )
    }

    private companion object {
        const val EVENT_TYPE = "AccountCreated"
        const val PAYLOAD_LOG_CHARS = 200
    }
}
