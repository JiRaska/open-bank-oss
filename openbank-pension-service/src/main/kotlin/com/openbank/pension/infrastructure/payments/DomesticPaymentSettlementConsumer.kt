// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.payments

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.pension.application.usecase.PayoutSettlementService
import com.openbank.pension.application.usecase.RailSettlement
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Brings a payout payment's settlement back from domestic-payment (#12378).
 *
 * `openbank.domestic.payment.events` is outbox-relayed; the discriminator is the `ce-type` header
 * only (asyncapi `domestic-payment-events`), so this reads the raw [ConsumerRecord]. Of the
 * `domestic.payment.status-changed` bodies it needs two keys — `paymentId` (the reference
 * [DomesticPayoutPaymentAdapter] returned to the payout workflow) and `newStatus`:
 * `SETTLED` → settled; `REJECTED`, `RETURNED`, `CANCELLED` → rejected; every other status is an
 * intermediate one and is ignored.
 *
 * Poison pills (an undecodable body, a non-UUID paymentId) are acked: a redelivery fails the same
 * way forever. WRITING the outcome is not in that class — a database outage is retried and then
 * rethrown, and the channel's `failure-strategy: dead-letter-queue` parks the record on
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
        val (paymentId, settlement) = decoded
        EventRetry.withRetry(log, "pension payout settlement", paymentId) {
            settlements.record(paymentId.toString(), settlement)
        }
    }

    /** Null = nothing to record: an intermediate status, or a poison pill (logged). */
    @Suppress("TooGenericExceptionCaught")
    internal fun decode(body: String?): Pair<UUID, RailSettlement>? {
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
        return paymentId to settlement
    }

    companion object {
        const val STATUS_CHANGED = "domestic.payment.status-changed"
    }
}
