// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.messaging

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fraud.application.port.out.PayeeHistoryRepository
import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.application.usecase.FeatureOnlineUpdater
import io.smallrye.common.annotation.Blocking
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Payload shape of openbank.transactions.transaction.initiated Kafka event. */
private data class TransactionInitiatedSignal(
    val aggregateId: UUID? = null,
    val sourceAccountId: UUID? = null,
    // ADR-0084 §3 v4: the payee/counterparty account for this transaction — already published by
    // transaction-service's TransactionInitiatedEvent, just not previously consumed here. Used as
    // the payee_history signal key; absent (e.g. external-rail payments with no internal target
    // account) means no payee-history row can be recorded for this signal.
    val targetAccountId: UUID? = null,
    val amount: BigDecimal? = null,
    val currencyCode: String? = null,
    // Business event time (required on TransactionInitiatedEvent) — the ADR-0140 as-of source.
    val occurredAt: Instant? = null,
)

private const val PAYLOAD_PREVIEW_LENGTH = 200

/**
 * Consumes `openbank.transactions.transaction.initiated` events (ADR-0084 §2) to build
 * per-account rolling velocity aggregates, and (ADR-0084 §3 v4) per-(account, payee) payment
 * history. Failures are DLQ'd — a missing signal means stale counters/history, not a missing
 * payment; scoring degrades gracefully (no aggregate = no velocity rule fires, no history row =
 * payee treated as new only via [com.openbank.fraud.application.usecase.FraudScoringService]'s own
 * fail-silent enrichment).
 */
@ApplicationScoped
class TransactionSignalConsumer(
    private val velocityRepo: VelocityAggregateRepository,
    private val payeeHistoryRepo: PayeeHistoryRepository,
    private val featureUpdater: FeatureOnlineUpdater,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(TransactionSignalConsumer::class.java)

    @Incoming("transaction-signal")
    @Blocking
    fun onTransactionInitiated(payload: String) {
        val signal = try {
            objectMapper.readValue(payload, TransactionInitiatedSignal::class.java)
        } catch (ex: JacksonException) {
            log.errorf(
                ex,
                "Failed to deserialise TransactionInitiatedSignal, dropping: %s",
                payload.take(PAYLOAD_PREVIEW_LENGTH),
            )
            return
        }
        val accountId = signal.sourceAccountId ?: return
        val amount = signal.amount ?: BigDecimal.ZERO
        val currency = signal.currencyCode ?: "CZK"
        @Suppress("TooGenericExceptionCaught") // DB / reactive layer exceptions have no common base
        runBlocking {
            try {
                velocityRepo.recordTransaction(accountId, amount, currency, signal.aggregateId)
                log.debugf("Velocity aggregate updated for account %s amount=%s %s", accountId, amount, currency)
            } catch (ex: Exception) {
                log.warnf(ex, "Failed to record velocity aggregate for account %s", accountId)
            }
            recordPayeeHistory(accountId, signal)
            // ADR-0140 online feature store (shadow plane). Best-effort: a feature-store failure must
            // never break the velocity path. Skip silently when the event carries no business time.
            val occurredAt = signal.occurredAt ?: return@runBlocking
            try {
                featureUpdater.onTransactionInitiated(accountId.toString(), occurredAt)
            } catch (ex: Exception) {
                log.warnf(ex, "Failed to update online feature store for account %s", accountId)
            }
        }
    }

    /**
     * ADR-0084 §3 v4: best-effort payee-history update. Skipped silently when the signal carries no
     * `targetAccountId` (e.g. an external-rail payment with no internal counterparty account) — same
     * "missing signal degrades gracefully, never breaks the velocity path" contract as the feature
     * store update below. Falls back to [clock] when `occurredAt` is absent so a history row is still
     * recorded (unlike the feature store, which requires a business timestamp to stay meaningful).
     */
    @Suppress("TooGenericExceptionCaught") // DB / reactive layer exceptions have no common base
    private suspend fun recordPayeeHistory(accountId: UUID, signal: TransactionInitiatedSignal) {
        val payeeIdentifier = signal.targetAccountId?.toString() ?: return
        val occurredAt = signal.occurredAt ?: Instant.now(clock)
        try {
            payeeHistoryRepo.recordPayment(accountId, payeeIdentifier, signal.aggregateId, occurredAt)
            log.debugf("Payee history updated for account %s payee %s", accountId, payeeIdentifier)
        } catch (ex: Exception) {
            log.warnf(ex, "Failed to record payee history for account %s payee %s", accountId, payeeIdentifier)
        }
    }
}
