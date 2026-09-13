// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.messaging

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.application.port.out.PayeeHistoryRepository
import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.application.usecase.FeatureOnlineUpdater
import com.openbank.libs.messaging.EventRetry
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
    private val metrics: FraudMetricsPort,
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
        // Issue #6044. occurredAt is REQUIRED on TransactionInitiatedEvent, so the fallback should
        // never be taken; if it is, every row written from this signal carries a business time
        // nobody measured, and the redelivery guard cannot reach a later replay of it (the replay
        // would substitute a different processing time and so target a different velocity bucket
        // row). Substituting silently is what made the audit consumer's ingest-time fallback
        // invisible for 7 of its 21 topics (#3883); this one is counted, so the absent case is
        // visible from outside the database instead of being indistinguishable from a healthy one.
        val eventTime = signal.occurredAt ?: run {
            metrics.recordSignalMissingEventTime()
            log.warnf(
                "transaction.initiated signal for account %s carries no occurredAt; substituting processing time. " +
                    "Velocity bucketing and redelivery dedupe are both degraded for this signal (#6044).",
                accountId,
            )
            Instant.now(clock)
        }
        runBlocking {
            // The velocity aggregate IS this consumer's product: every velocity rule reads it, so a
            // dropped update silently weakens fraud detection for that account with no error anywhere
            // (an acked Kafka message is indistinguishable from a processed one). Retried, then
            // RETHROWN so the connector dead-letters — #5698. recordTransaction is an idempotent
            // upsert on (account, window).
            EventRetry.withRetry(log, "velocity aggregate", accountId) {
                velocityRepo.recordTransaction(accountId, amount, currency, signal.aggregateId, eventTime)
                log.debugf("Velocity aggregate updated for account %s amount=%s %s", accountId, amount, currency)
            }
            recordPayeeHistory(accountId, signal, eventTime)
            // ADR-0140 online feature store (SHADOW plane).
            // best-effort: nothing decides on it — it feeds a model running in shadow, so a gap costs
            // observability, not a fraud verdict. That is the test for whether a catch may swallow
            // (#5698): can the event be called complete without this side effect? Here, yes.
            // Skipped silently when the event carries no business time.
            val occurredAt = signal.occurredAt ?: return@runBlocking
            @Suppress("TooGenericExceptionCaught") // shadow-plane side effect; see the marker above
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
     * store update below. [eventTime] is the signal's `occurredAt`, or processing time when it
     * carries none, so a history row is still recorded (unlike the feature store, which requires a
     * business timestamp to stay meaningful) — the substitution is counted by the caller (#6044).
     */
    private suspend fun recordPayeeHistory(accountId: UUID, signal: TransactionInitiatedSignal, occurredAt: Instant) {
        val payeeIdentifier = signal.targetAccountId?.toString() ?: return
        // A MISSING signal (no targetAccountId) degrades gracefully — that is the documented contract
        // above and it stays. A FAILED write does not: "first payment to this payee" is a fraud
        // discriminator, and a hole in the history reads as a first-time payee forever after.
        EventRetry.withRetry(log, "payee history", accountId) {
            payeeHistoryRepo.recordPayment(accountId, payeeIdentifier, signal.aggregateId, occurredAt)
            log.debugf("Payee history updated for account %s payee %s", accountId, payeeIdentifier)
        }
    }
}
