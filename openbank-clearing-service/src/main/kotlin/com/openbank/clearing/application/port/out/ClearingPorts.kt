// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.application.port.out

import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.clearing.domain.model.PaymentRail
import com.openbank.clearing.domain.model.SettlementPosition
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.util.UUID

/** Outbound persistence port for clearing batches (settlement cycles per rail). */
interface ClearingBatchRepository {

    fun save(batch: ClearingBatch): Uni<ClearingBatch>

    fun findById(id: UUID): Uni<ClearingBatch?>

    fun findByStatus(status: ClearingStatus): Uni<List<ClearingBatch>>

    fun findAll(page: Int, size: Int): Uni<List<ClearingBatch>>

    fun update(batch: ClearingBatch): Uni<ClearingBatch>

    /**
     * Settle atomically (#8509): the batch state change, the item status flips and the outbox
     * row for `event` commit in ONE transaction. Before this, `ClearingService.settleBatch`
     * composed `update` + `saveAll` + `publishBatchSettled`, each opening its own transaction
     * (measured: batch xmin 750, outbox xmin 752) — a crash after the batch commit lost
     * `openbank.clearing.batch.settled` permanently, the exact failure the transactional outbox
     * exists to prevent. The composition lives here, in infrastructure, for the same reason
     * sanctions' `saveWithEvent` does: a `@WithTransaction` boundary in the use-case layer makes
     * the service untestable without a Vert.x context.
     */
    fun settleWithEvent(batch: ClearingBatch, items: List<ClearingItem>, event: OutboxMessage): Uni<ClearingBatch> =
        settleWithEvents(batch, items, listOf(event))

    /**
     * The multi-event form of [settleWithEvent] (ADR-0281): settle commits not only the
     * `batch.settled` event but also the `net_settlement.post` command — the durable intent to
     * post the net-settlement journal — in the SAME transaction as the state change, so a batch
     * can never read SETTLED while its settlement leg is nowhere durable. Same single-transaction
     * oracle (`xmin`) as #8509.
     */
    fun settleWithEvents(
        batch: ClearingBatch,
        items: List<ClearingItem>,
        events: List<OutboxMessage>,
    ): Uni<ClearingBatch>
}

/** Outbound persistence port for individual clearing items (payments in a batch). */
interface ClearingItemRepository {

    fun save(item: ClearingItem): Uni<ClearingItem>

    fun saveAll(items: List<ClearingItem>): Uni<List<ClearingItem>>

    fun findById(id: UUID): Uni<ClearingItem?>

    fun findByBatchId(batchId: UUID): Uni<List<ClearingItem>>

    fun findByPaymentId(paymentId: UUID): Uni<List<ClearingItem>>

    fun findPendingByRail(rail: PaymentRail, limit: Int): Uni<List<ClearingItem>>

    fun updateStatus(id: UUID, status: ClearingStatus, errorCode: String?, errorMessage: String?): Uni<Int>
}

/** Outbound persistence port for per-participant net settlement positions. */
interface SettlementPositionRepository {

    fun save(position: SettlementPosition): Uni<SettlementPosition>

    fun findByCycleId(cycleId: String): Uni<List<SettlementPosition>>

    fun upsertPosition(
        participantBic: String,
        currency: String,
        cycleId: String,
        debit: BigDecimal,
        credit: BigDecimal,
    ): Uni<SettlementPosition>
}

/** Outbound port for publishing clearing domain events to the broker. */
interface ClearingEventPublisher {

    fun publishBatchSettled(batch: ClearingBatch): Uni<Void>

    /**
     * The ready-made outbox message for `batch.settled`, WITHOUT persisting it — the caller owns
     * the transaction. `ClearingService.settleBatch` hands this to
     * `ClearingBatchRepository.settleWithEvent` so the event row commits with the state change
     * (#8509); [publishBatchSettled] is the standalone form (own transaction) for any caller
     * that has no state change of its own.
     */
    fun batchSettledMessage(batch: ClearingBatch): OutboxMessage

    /**
     * The ready-made outbox message for the `net_settlement.post` COMMAND (ADR-0281), WITHOUT
     * persisting it — the caller owns the transaction. `ClearingService.settleBatch` hands this
     * to `ClearingBatchRepository.settleWithEvents` together with [batchSettledMessage] so the
     * durable intent to post the net-settlement journal commits with the state change; the
     * `NetSettlementPostingConsumer` turns it into the actual ledger journal idempotently.
     */
    fun netSettlementPostMessage(batch: ClearingBatch): OutboxMessage

    fun publishItemCleared(item: ClearingItem): Uni<Void>
}
