// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.payments

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.pension.application.usecase.PayoutSettlementService
import com.openbank.pension.application.usecase.RailSettlement
import com.openbank.pension.application.usecase.SettlementOutcome
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * Brings a payout payment's settlement back from domestic-payment (#12378).
 *
 * `openbank.domestic.payment.events` is outbox-relayed; the discriminator is the `ce-type` header
 * only (asyncapi `domestic-payment-events`), so this reads the raw [ConsumerRecord]. Of the
 * `domestic.payment.status-changed` bodies it needs `paymentId` (the reference
 * [DomesticPayoutPaymentAdapter] returned to the payout workflow), `newStatus` and the domestic
 * status event's `occurredAt` and, for SETTLED, the payment's persisted `settledAt`. The latter
 * is the reporting date; it may precede both event creation and Kafka consumption:
 * `SETTLED` → settled; `REJECTED`, `RETURNED`, `CANCELLED` → rejected; every other status is an
 * intermediate one and is ignored.
 *
 * Poison pills (an undecodable body, a non-UUID paymentId) are acked: a redelivery fails the same
 * way forever. A SETTLED event lacking the persisted source time is not acknowledged: it must be
 * recovered from the source, not guessed. WRITING the outcome is retried and then rethrown, and
 * the channel's `failure-strategy: dead-letter-queue` parks the record on
 * `openbank.dlq.pension.domestic-payment-events-in` instead of acking a settlement nobody recorded
 * (#5698/#5745). Replays are harmless: the write is a guarded transition and changes a row once.
 */
@ApplicationScoped
class DomesticPaymentSettlementConsumer(
    private val objectMapper: ObjectMapper,
    private val settlements: PayoutSettlementService,
) {
    private val log = Logger.getLogger(DomesticPaymentSettlementConsumer::class.java)

    @Incoming("domestic-payment-events-in")
    suspend fun consume(record: ConsumerRecord<String, String>) {
        val type = record.headers().lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)
            ?.let { String(it.value(), StandardCharsets.UTF_8) }
        if (type != STATUS_CHANGED) return
        val decoded = decode(record.value()) ?: return
        val outcome = EventRetry.withRetry(log, "pension payout settlement", decoded.paymentId) {
            settlements.record(
                decoded.paymentId.toString(),
                decoded.settlement,
                decoded.occurredAt,
                decoded.settledAt,
            )
        }
        if (
            outcome != SettlementOutcome.NOT_OURS &&
            decoded.settlement == RailSettlement.SETTLED &&
            decoded.settledAt == null
        ) {
            error("domestic SETTLED event lacks persisted settledAt; payout reporting is blocked")
        }
    }

    /** Null = nothing to record: an intermediate status, or a poison pill (logged). */
    @Suppress("TooGenericExceptionCaught")
    internal fun decode(body: String?): DecodedSettlement? {
        val node = try {
            objectMapper.readTree(body ?: return null)
        } catch (e: Exception) {
            log.errorf(e, "[pension-settlement] undecodable domestic payment event, acking: %.200s", body)
            return null
        }
        val settlement = when (node.path("newStatus").asText()) {
            "SETTLED" -> RailSettlement.SETTLED
            "REJECTED", "RETURNED", "CANCELLED" -> RailSettlement.REJECTED
            else -> return null
        }
        val paymentId = runCatching { UUID.fromString(node.path("paymentId").asText()) }.getOrElse {
            log.errorf("[pension-settlement] status change without a valid paymentId, acking: %.200s", body)
            return null
        }
        val occurredAt = runCatching { Instant.parse(node.path("occurredAt").asText()) }.getOrElse {
            log.errorf("[pension-settlement] status change without a valid occurredAt, acking: %.200s", body)
            return null
        }
        // A missing/invalid source time is durably recorded as SETTLED/null before throwing to
        // DLQ. The reporting completeness guard then refuses figures until reconciliation.
        val settledAt = if (settlement == RailSettlement.SETTLED) {
            node.path("settledAt").takeUnless { it.isMissingNode || it.isNull }
                ?.let { runCatching { Instant.parse(it.asText()) }.getOrNull() }
        } else {
            null
        }
        return DecodedSettlement(paymentId, settlement, occurredAt, settledAt)
    }

    internal data class DecodedSettlement(
        val paymentId: UUID,
        val settlement: RailSettlement,
        val occurredAt: Instant,
        val settledAt: Instant?,
    )

    companion object {
        const val STATUS_CHANGED = "domestic.payment.status-changed"
    }
}
