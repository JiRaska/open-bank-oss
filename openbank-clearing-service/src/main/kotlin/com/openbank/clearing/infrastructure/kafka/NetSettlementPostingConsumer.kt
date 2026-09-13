// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.clearing.application.port.out.ClearingLedgerPostingPort
import com.openbank.clearing.application.port.out.NetSettlementPosting
import com.openbank.libs.messaging.EventRetry
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Posts the net-settlement journal for every settled clearing batch (ADR-0281, issue #8361).
 *
 * `openbank.clearing.batch.event` carries every clearing domain event on one topic; this consumer
 * self-subscribes via `clearing-net-settlement-in` (same topic, a second consumer group) and
 * filters for [ClearingEventPublisherImpl.NET_SETTLEMENT_POST_EVENT] — committed atomically with
 * the batch's SETTLED state by `ClearingService.settleBatch` — ignoring every other event type
 * (the `batch.settled` notification and `item.cleared`). This is the same self-consumption shape
 * as interest-service's withholding settlement consumer: the service that settles the batch is
 * also the one that drives its settlement leg, rather than a cross-service choreography.
 *
 * **Event-type filtering is payload-based**: clearing's publisher duplicates `eventType` into the
 * JSON body (its own convention, unlike the header-only interest publisher).
 *
 * Poison-pill safety applies to the PAYLOAD only: an unparseable body or an undecodable field is
 * acked, because a redelivery fails identically forever. POSTING the journal is not in that
 * class — it moves settlement money — so a failing post is retried via [EventRetry] and rethrown
 * for the connector to dead-letter (#5698; the DLQ is wired on the channel, see application.yaml).
 *
 * Idempotency: the command carries the deterministic `clearing-net-settlement-{batchId}` key and
 * ledger-service collapses a redelivery onto the one booked journal, so Kafka at-least-once
 * delivery can never double-settle a batch.
 */
@ApplicationScoped
class NetSettlementPostingConsumer(
    private val objectMapper: ObjectMapper,
    private val postingPort: ClearingLedgerPostingPort,
) {
    private val log = Logger.getLogger(NetSettlementPostingConsumer::class.java)

    @Incoming("clearing-net-settlement-in")
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(record: ConsumerRecord<String, String>) {
        val node = try {
            objectMapper.readTree(record.value())
        } catch (e: Exception) {
            log.errorf(e, "[net-settlement] undecodable event body, acking: %.300s", record.value())
            return
        }
        if (node.path("eventType").asText() != ClearingEventPublisherImpl.NET_SETTLEMENT_POST_EVENT) return

        val posting = try {
            NetSettlementPosting(
                batchId = UUID.fromString(node.path("batchId").asText()),
                batchReference = node.path("batchReference").asText(),
                cycleId = node.path("cycleId").asText(),
                idempotencyKey = node.path("idempotencyKey").asText(),
                currency = node.path("currency").asText(),
                settlementAmount = BigDecimal(node.path("settlementAmount").asText()),
                valueDate = LocalDate.parse(node.path("valueDate").asText()),
            )
        } catch (e: Exception) {
            log.errorf(e, "[net-settlement] undecodable command fields, acking: %.300s", record.value())
            return
        }

        EventRetry.withRetry(log, "net settlement posting", posting.batchId) {
            postingPort.postNetSettlement(posting).awaitSuspending()
        }
        log.infof(
            "[net-settlement] posted journal for batch %s (%s %s, key %s)",
            posting.batchId,
            posting.settlementAmount,
            posting.currency,
            posting.idempotencyKey,
        )
    }
}
