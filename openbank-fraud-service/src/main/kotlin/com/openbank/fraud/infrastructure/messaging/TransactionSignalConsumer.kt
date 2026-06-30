// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.messaging

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.application.usecase.FeatureOnlineUpdater
import io.smallrye.common.annotation.Blocking
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Payload shape of openbank.transactions.transaction.initiated Kafka event. */
private data class TransactionInitiatedSignal(
    val aggregateId: UUID? = null,
    val sourceAccountId: UUID? = null,
    val amount: BigDecimal? = null,
    val currencyCode: String? = null,
    // Business event time (required on TransactionInitiatedEvent) — the ADR-0140 as-of source.
    val occurredAt: Instant? = null,
)

private const val PAYLOAD_PREVIEW_LENGTH = 200

/**
 * Consumes `openbank.transactions.transaction.initiated` events (ADR-0084 §2) to build
 * per-account rolling velocity aggregates. Failures are DLQ'd — a missing signal means
 * stale counters, not a missing payment; scoring degrades gracefully (no aggregate = no
 * velocity rule fires).
 */
@ApplicationScoped
class TransactionSignalConsumer(
    private val velocityRepo: VelocityAggregateRepository,
    private val featureUpdater: FeatureOnlineUpdater,
    private val objectMapper: ObjectMapper,
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
                velocityRepo.recordTransaction(accountId, amount, currency)
                log.debugf("Velocity aggregate updated for account %s amount=%s %s", accountId, amount, currency)
            } catch (ex: Exception) {
                log.warnf(ex, "Failed to record velocity aggregate for account %s", accountId)
            }
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
}
