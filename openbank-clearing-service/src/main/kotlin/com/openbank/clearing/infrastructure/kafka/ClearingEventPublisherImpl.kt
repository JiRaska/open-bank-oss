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

@ApplicationScoped
class ClearingEventPublisherImpl @Inject constructor(
    private val objectMapper: ObjectMapper,
    private val outboxRepo: ClearingOutboxRepository,
) : ClearingEventPublisher {

    companion object {
        private const val BATCH_SETTLED_EVENT = "openbank.clearing.batch.settled"
        private const val ITEM_CLEARED_EVENT = "openbank.clearing.item.cleared"
    }

    override fun publishBatchSettled(batch: ClearingBatch): Uni<Void> {
        val message = OutboxMessage(
            aggregateId = batch.id,
            eventType = BATCH_SETTLED_EVENT,
            payload = objectMapper.writeValueAsString(
                mapOf(
                    // eventType is also on OutboxMessage.eventType (-> Kafka header, ce-type), but
                    // AuditConsumer (PR #1007) reads only the JSON body, so it is duplicated here.
                    "eventType" to BATCH_SETTLED_EVENT,
                    "batchId" to batch.id,
                    "batchReference" to batch.batchReference,
                    "rail" to batch.rail.name,
                    "cycleId" to batch.cycleId,
                    "totalDebit" to batch.totalDebit,
                    "totalCredit" to batch.totalCredit,
                    "netPosition" to batch.netPosition,
                    "itemCount" to batch.itemCount,
                    "settledAt" to batch.settledAt?.toString(),
                ),
            ),
        )
        return Panache.withTransaction { outboxRepo.persistInTransaction(message) }
    }

    override fun publishItemCleared(item: ClearingItem): Uni<Void> {
        val message = OutboxMessage(
            aggregateId = item.id,
            eventType = ITEM_CLEARED_EVENT,
            payload = objectMapper.writeValueAsString(
                mapOf(
                    "eventType" to ITEM_CLEARED_EVENT,
                    "itemId" to item.id,
                    "batchId" to item.batchId,
                    "paymentId" to item.paymentId,
                    "paymentReference" to item.paymentReference,
                    "amount" to item.amount,
                    "currency" to item.currency,
                    "status" to item.status.name,
                ),
            ),
        )
        return Panache.withTransaction { outboxRepo.persistInTransaction(message) }
    }
}
