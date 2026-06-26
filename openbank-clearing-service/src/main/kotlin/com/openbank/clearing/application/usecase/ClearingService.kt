// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.clearing.application.usecase

import com.openbank.clearing.application.port.`in`.*
import com.openbank.clearing.application.port.out.*
import com.openbank.clearing.domain.model.*
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
    GetPositionsUseCase {

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
                val totalDebit = items.fold(BigDecimal.ZERO) { acc, i -> acc + i.amount }
                val batch = ClearingBatch(
                    batchReference = cycleId,
                    rail = rail,
                    settlementType = SettlementType.NET,
                    status = ClearingStatus.IN_CLEARING,
                    totalDebit = totalDebit,
                    totalCredit = totalDebit,
                    netPosition = BigDecimal.ZERO,
                    currency = items.first().currency,
                    itemCount = items.size,
                    cycleId = cycleId,
                    settlementDate = LocalDate.now(clock),
                    createdAt = now,
                    updatedAt = now,
                )
                batchRepo.save(batch).flatMap { savedBatch ->
                    // Update all items with batch ID
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
            val settled = batch.copy(
                status = ClearingStatus.SETTLED,
                settledAt = OffsetDateTime.now(clock),
                updatedAt = OffsetDateTime.now(clock),
            )
            batchRepo.update(settled).flatMap { savedBatch ->
                eventPublisher.publishBatchSettled(savedBatch).map { savedBatch }
            }
        }
    }

    override fun getPositions(cycleId: String): Uni<List<SettlementPosition>> = positionRepo.findByCycleId(cycleId)
}
