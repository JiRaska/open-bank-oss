// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.application.usecase

import com.openbank.clearing.application.port.`in`.GetBatchUseCase
import com.openbank.clearing.application.port.`in`.GetItemUseCase
import com.openbank.clearing.application.port.`in`.GetPositionsUseCase
import com.openbank.clearing.application.port.`in`.ReconcileUseCase
import com.openbank.clearing.application.port.`in`.SubmitPaymentUseCase
import com.openbank.clearing.application.port.`in`.TriggerClearingUseCase
import com.openbank.clearing.application.port.out.ClearingBatchRepository
import com.openbank.clearing.application.port.out.ClearingEventPublisher
import com.openbank.clearing.application.port.out.ClearingItemRepository
import com.openbank.clearing.application.port.out.SettlementPositionRepository
import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.clearing.domain.model.PaymentRail
import com.openbank.clearing.domain.model.ReconciliationReport
import com.openbank.clearing.domain.model.SettlementPosition
import com.openbank.clearing.domain.model.SettlementType
import com.openbank.clearing.domain.model.SubmitPaymentRequest
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@ApplicationScoped
class ClearingService(
    private val batchRepo: ClearingBatchRepository,
    private val itemRepo: ClearingItemRepository,
    private val positionRepo: SettlementPositionRepository,
    private val eventPublisher: ClearingEventPublisher,
    private val clock: Clock,
) : SubmitPaymentUseCase,
    GetBatchUseCase,
    GetItemUseCase,
    TriggerClearingUseCase,
    GetPositionsUseCase,
    ReconcileUseCase {

    @Retry(maxRetries = 3)
    override fun submit(request: SubmitPaymentRequest): Uni<ClearingItem> {
        val now = OffsetDateTime.now(clock)
        val item = ClearingItem(
            batchId = UUID.fromString("00000000-0000-0000-0000-000000000000"), // assigned during clearing
            paymentId = request.paymentId,
            paymentReference = request.paymentReference,
            debtorIban = request.debtorIban,
            creditorIban = request.creditorIban,
            debtorBic = request.debtorBic,
            creditorBic = request.creditorBic,
            amount = request.amount,
            currency = request.currency,
            status = ClearingStatus.PENDING,
            valueDate = request.valueDate ?: LocalDate.now(clock),
            endToEndId = request.endToEndId,
            remittanceInfo = request.remittanceInfo,
            createdAt = now,
            updatedAt = now,
        )
        return itemRepo.save(item)
    }

    override fun getBatch(id: UUID): Uni<ClearingBatch?> = batchRepo.findById(id)

    override fun listBatches(status: ClearingStatus?, page: Int, size: Int): Uni<List<ClearingBatch>> =
        if (status != null) {
            batchRepo.findByStatus(status)
        } else {
            batchRepo.findAll(page, size)
        }

    override fun getItem(id: UUID): Uni<ClearingItem?> = itemRepo.findById(id)
    override fun listItemsByBatch(batchId: UUID): Uni<List<ClearingItem>> = itemRepo.findByBatchId(batchId)
    override fun listItemsByPayment(paymentId: UUID): Uni<List<ClearingItem>> = itemRepo.findByPaymentId(paymentId)

    @Timeout(value = 30000)
    override fun triggerClearingCycle(rail: PaymentRail): Uni<ClearingBatch> {
        val cycleId = "CYCLE-${rail.name}-${LocalDate.now(clock).format(
            DateTimeFormatter.BASIC_ISO_DATE,
        )}-${clock.millis() % 10000}"
        return itemRepo.findPendingByRail(rail, 1000).flatMap { items ->
            if (items.isEmpty()) {
                val now = OffsetDateTime.now(clock)
                val emptyBatch = ClearingBatch(
                    batchReference = cycleId,
                    rail = rail,
                    status = ClearingStatus.SETTLED,
                    cycleId = cycleId,
                    settlementDate = LocalDate.now(clock),
                    createdAt = now,
                    updatedAt = now,
                )
                batchRepo.save(emptyBatch)
            } else {
                val now = OffsetDateTime.now(clock)
                // For GROSS settlement: every item is a debit from our participant's perspective.
                // totalCredit = gross sum of incoming amounts for the counterparty rail leg.
                // For NET batches these will be equal (bilateral exchange); for multi-lateral
                // netting positionRepo.upsertPosition() is used per participant in settleBatch().
                val totalDebit = items.fold(BigDecimal.ZERO) { acc, i -> acc + i.amount }
                val totalCredit = totalDebit // bilateral: same volume on both legs
                val netPosition = totalDebit - totalCredit // net exposure after offset
                val batch = ClearingBatch(
                    batchReference = cycleId,
                    rail = rail,
                    settlementType = SettlementType.NET,
                    status = ClearingStatus.IN_CLEARING,
                    totalDebit = totalDebit,
                    totalCredit = totalCredit,
                    netPosition = netPosition,
                    currency = items.first().currency,
                    itemCount = items.size,
                    cycleId = cycleId,
                    settlementDate = LocalDate.now(clock),
                    createdAt = now,
                    updatedAt = now,
                )
                batchRepo.save(batch).flatMap { savedBatch ->
                    val updatedItems = items.map {
                        it.copy(batchId = savedBatch.id, status = ClearingStatus.IN_CLEARING)
                    }
                    itemRepo.saveAll(updatedItems).map { savedBatch }
                }
            }
        }
    }

    override fun settleBatch(batchId: UUID): Uni<ClearingBatch> = batchRepo.findById(batchId).flatMap { batch ->
        if (batch == null) {
            Uni.createFrom().failure(IllegalArgumentException("Batch not found: $batchId"))
        } else {
            check(batch.status == ClearingStatus.IN_CLEARING) {
                "Cannot settle batch in status ${batch.status}"
            }
            val now = OffsetDateTime.now(clock)
            val settled = batch.copy(
                status = ClearingStatus.SETTLED,
                settledAt = now,
                updatedAt = now,
            )
            // #8509: ONE transaction for batch + items + outbox rows, owned by the repository
            // (settleWithEvents) — composing update/saveAll/publish here gave each its own
            // transaction (measured xmin 750 vs 752) and could lose the settled event on a crash
            // between commits. The boundary lives in infrastructure so this use case stays
            // unit-testable without a Vert.x context (the sanctions `saveWithEvent` shape).
            // ADR-0281: the net_settlement.post command commits in the SAME transaction — a
            // SETTLED batch always has its settlement-leg intent durable; the actual journal
            // posting is the consumer's idempotent job.
            itemRepo.findByBatchId(batchId).flatMap { items ->
                val updatedItems = items.map { it.copy(status = ClearingStatus.SETTLED) }
                batchRepo.settleWithEvents(
                    settled,
                    updatedItems,
                    listOf(
                        eventPublisher.batchSettledMessage(settled),
                        eventPublisher.netSettlementPostMessage(settled),
                    ),
                )
            }
        }
    }

    override fun getPositions(cycleId: String): Uni<List<SettlementPosition>> = positionRepo.findByCycleId(cycleId)

    override fun reconcileBatch(batchId: UUID): Uni<ReconciliationReport> =
        batchRepo.findById(batchId).flatMap { batch ->
            if (batch == null) {
                Uni.createFrom().failure(IllegalArgumentException("Batch not found: $batchId"))
            } else {
                itemRepo.findByBatchId(batchId).map { items ->
                    val stuckIds = items
                        .filter { it.status != ClearingStatus.SETTLED && it.status != ClearingStatus.REVERSED }
                        .map { it.id }
                    ReconciliationReport(
                        batchId = batchId,
                        cycleId = batch.cycleId,
                        expectedItemCount = batch.itemCount,
                        settledItemCount = items.count { it.status == ClearingStatus.SETTLED },
                        stuckItemIds = stuckIds,
                        checkedAt = OffsetDateTime.now(clock),
                    )
                }
            }
        }
}
