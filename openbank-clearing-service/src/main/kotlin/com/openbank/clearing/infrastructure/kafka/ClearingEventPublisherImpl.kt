// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.clearing.application.port.out.ClearingEventPublisher
import com.openbank.clearing.application.port.out.ClearingOutboxRepository
import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

@ApplicationScoped
class ClearingEventPublisherImpl @Inject constructor(
    private val objectMapper: ObjectMapper,
    private val outboxRepo: ClearingOutboxRepository,
) : ClearingEventPublisher {

    companion object {
        private const val BATCH_SETTLED_EVENT = "openbank.clearing.batch.settled"
        private const val ITEM_CLEARED_EVENT = "openbank.clearing.item.cleared"

        /**
         * The net-settlement COMMAND event (ADR-0281): the durable intent to post the batch's
         * net-settlement journal to ledger-service, committed atomically with the batch's SETTLED
         * state. Consumed by `NetSettlementPostingConsumer` on this service's own topic.
         */
        const val NET_SETTLEMENT_POST_EVENT = "openbank.clearing.net_settlement.post"

        /**
         * The deterministic idempotency key of a batch's net-settlement journal (ADR-0281) — the
         * producer writes it into the command payload, the consumer posts with it, and
         * ledger-service's idempotency collapses any redelivery onto the one booked journal.
         */
        fun netSettlementIdempotencyKey(batchId: UUID): String = "clearing-net-settlement-$batchId"

        /**
         * Producing service, read by `AuditConsumer.resolveSourceService` as the strongest
         * (EVENT-sourced) attribution (#3994/#5256). Matches the fleet's audit convention — the
         * module directory without the `openbank-` prefix — the same spelling `EventAttribution`
         * (`TopicAttribution`) already maps `openbank.clearing.batch.event` (the real outgoing
         * Kafka topic for the `clearing-events-out` channel both events below publish on) to.
         * Audit-service subscribes to that topic today (`application.yaml`'s consumed-topics
         * list), so this is a live attribution upgrade, not a forward-looking one.
         */
        private const val SOURCE_SERVICE = "clearing-service"
    }

    override fun publishBatchSettled(batch: ClearingBatch): Uni<Void> =
        Panache.withTransaction { outboxRepo.persistInTransaction(batchSettledMessage(batch)) }

    override fun batchSettledMessage(batch: ClearingBatch): OutboxMessage = OutboxMessage(
        aggregateId = batch.id,
        eventType = BATCH_SETTLED_EVENT,
        payload = batchSettledPayload(batch),
    )

    /**
     * The `batch.settled` JSON body, split out from [publishBatchSettled] so it can be asserted
     * without a Vert.x context (`Panache.withTransaction` needs one; a payload does not). #3914.
     */
    internal fun batchSettledPayload(batch: ClearingBatch): String = objectMapper.writeValueAsString(
        mapOf(
            // eventType is also on OutboxMessage.eventType (-> Kafka header, ce-type), but
            // AuditConsumer (PR #1007) reads only the JSON body, so it is duplicated here.
            "eventType" to BATCH_SETTLED_EVENT,
            "sourceService" to SOURCE_SERVICE,
            "batchId" to batch.id,
            "batchReference" to batch.batchReference,
            "rail" to batch.rail.name,
            "cycleId" to batch.cycleId,
            "totalDebit" to batch.totalDebit,
            "totalCredit" to batch.totalCredit,
            "netPosition" to batch.netPosition,
            "itemCount" to batch.itemCount,
            "settledAt" to batch.settledAt?.toString(),
            // #3914: the business event time, not the serialisation time. `settledAt` IS
            // the settlement instant (ClearingService.settleBatch sets it on the aggregate
            // before this publisher ever runs); `updatedAt` is the fallback for a batch
            // whose settledAt was never stamped. Emitted as an Instant, NOT via
            // OffsetDateTime.toString() — AuditConsumer.eventTime() parses with
            // Instant.parse, which rejects a non-Z offset ("...+01:00") and would fall
            // back to ingest time, i.e. exactly the defect this line exists to fix.
            "occurredAt" to (batch.settledAt ?: batch.updatedAt).toInstant().toString(),
        ),
    )

    override fun netSettlementPostMessage(batch: ClearingBatch): OutboxMessage = OutboxMessage(
        aggregateId = batch.id,
        eventType = NET_SETTLEMENT_POST_EVENT,
        payload = netSettlementPostPayload(batch),
    )

    /**
     * The `net_settlement.post` JSON body (ADR-0281): everything the posting consumer needs to
     * build the journal without a read-back — the idempotency key (deterministic per batch, so a
     * redelivered command collapses onto the already-booked journal), the settlement amounts and
     * the value date. GL account resolution lives in `NetSettlementJournalFactory` (the consumer
     * side), keeping this payload free of ledger chart-of-accounts internals.
     */
    internal fun netSettlementPostPayload(batch: ClearingBatch): String = objectMapper.writeValueAsString(
        mapOf(
            "eventType" to NET_SETTLEMENT_POST_EVENT,
            "sourceService" to SOURCE_SERVICE,
            "batchId" to batch.id,
            "batchReference" to batch.batchReference,
            "cycleId" to batch.cycleId,
            "idempotencyKey" to netSettlementIdempotencyKey(batch.id),
            "currency" to batch.currency,
            // The bilateral model settles the full gross volume per leg (netPosition is zero by
            // construction today); the journal moves totalDebit between the cash-clearing and
            // scheme-settlement GLs. The field is named for what it is so a future multi-lateral
            // model changing the amount is a schema-visible change, not a silent semantic shift.
            "settlementAmount" to batch.totalDebit,
            "valueDate" to batch.settlementDate.toString(),
            "occurredAt" to (batch.settledAt ?: batch.updatedAt).toInstant().toString(),
        ),
    )

    override fun publishItemCleared(item: ClearingItem): Uni<Void> {
        val message = OutboxMessage(
            aggregateId = item.id,
            eventType = ITEM_CLEARED_EVENT,
            payload = itemClearedPayload(item),
        )
        return Panache.withTransaction { outboxRepo.persistInTransaction(message) }
    }

    /** The `item.cleared` JSON body. See [batchSettledPayload] for why this is split out. */
    internal fun itemClearedPayload(item: ClearingItem): String = objectMapper.writeValueAsString(
        mapOf(
            "eventType" to ITEM_CLEARED_EVENT,
            "sourceService" to SOURCE_SERVICE,
            "itemId" to item.id,
            "batchId" to item.batchId,
            "paymentId" to item.paymentId,
            "paymentReference" to item.paymentReference,
            "amount" to item.amount,
            "currency" to item.currency,
            "status" to item.status.name,
            // #3914: ClearingItem has no per-transition instant, so `updatedAt` — the
            // instant the row last changed state, which for a cleared item IS the clearing
            // — is the truest available business time. Same Instant-vs-offset note as
            // publishBatchSettled above.
            "occurredAt" to item.updatedAt.toInstant().toString(),
        ),
    )
}
