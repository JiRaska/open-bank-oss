// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.application.usecase

import com.openbank.clearing.application.port.out.ClearingBatchRepository
import com.openbank.clearing.application.port.out.ClearingEventPublisher
import com.openbank.clearing.application.port.out.ClearingItemRepository
import com.openbank.clearing.application.port.out.SettlementPositionRepository
import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.clearing.domain.model.PaymentRail
import com.openbank.clearing.domain.model.SubmitPaymentRequest
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ClearingServiceTest {

    private val batchRepo = mockk<ClearingBatchRepository>()
    private val itemRepo = mockk<ClearingItemRepository>()
    private val positionRepo = mockk<SettlementPositionRepository>()
    private val eventPublisher = mockk<ClearingEventPublisher>()
    private val fixedClock = Clock.fixed(Instant.parse("2026-01-20T10:00:00Z"), ZoneOffset.UTC)
    private val fixedNow = OffsetDateTime.now(fixedClock)
    private val service = ClearingService(batchRepo, itemRepo, positionRepo, eventPublisher, fixedClock)

    @Test
    fun `submit saves clearing item with pending status`() {
        val request = SubmitPaymentRequest(
            paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            paymentReference = "PAY-001",
            debtorIban = "DE89370400440532013000",
            creditorIban = "DE12500105170648489890",
            debtorBic = "DEUTDEFF",
            creditorBic = "COBADEFF",
            amount = BigDecimal("125.50"),
            currency = "EUR",
            valueDate = LocalDate.of(2026, 1, 20),
            endToEndId = "E2E-001",
            remittanceInfo = "Invoice 42",
        )
        val savedItem = request.toExpectedItem()
        val itemSlot: CapturingSlot<ClearingItem> = slot()

        every { itemRepo.save(capture(itemSlot)) } returns Uni.createFrom().item(savedItem)

        val result = service.submit(request).await().indefinitely()

        assertThat(result).isEqualTo(savedItem)
        assertThat(itemSlot.captured.paymentId).isEqualTo(request.paymentId)
        assertThat(itemSlot.captured.paymentReference).isEqualTo(request.paymentReference)
        assertThat(itemSlot.captured.debtorIban).isEqualTo(request.debtorIban)
        assertThat(itemSlot.captured.creditorIban).isEqualTo(request.creditorIban)
        assertThat(itemSlot.captured.amount).isEqualByComparingTo(request.amount)
        assertThat(itemSlot.captured.currency).isEqualTo(request.currency)
        assertThat(itemSlot.captured.status).isEqualTo(ClearingStatus.PENDING)
        verify(exactly = 1) { itemRepo.save(any()) }
    }

    @Test
    fun `settle batch transitions batch to settled, marks items settled, and publishes event`() {
        val batchId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val batch = ClearingBatch(
            id = batchId,
            batchReference = "BATCH-002",
            rail = PaymentRail.SEPA_SCT,
            status = ClearingStatus.IN_CLEARING,
            itemCount = 2,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )
        val items = listOf(
            clearingItem(batchId = batchId, status = ClearingStatus.IN_CLEARING),
            clearingItem(batchId = batchId, status = ClearingStatus.IN_CLEARING),
        )
        val updatedSlot: CapturingSlot<ClearingBatch> = slot()
        val itemsSlot: CapturingSlot<List<ClearingItem>> = slot()
        val savedBatch = batch.copy(
            status = ClearingStatus.SETTLED,
            settledAt = OffsetDateTime.parse("2026-01-20T10:15:30Z"),
            updatedAt = OffsetDateTime.parse("2026-01-20T10:15:31Z"),
        )

        every { batchRepo.findById(batchId) } returns Uni.createFrom().item(batch)
        every { itemRepo.findByBatchId(batchId) } returns Uni.createFrom().item(items)
        every { eventPublisher.batchSettledMessage(any()) } returns mockk()
        every { eventPublisher.netSettlementPostMessage(any()) } returns mockk()
        val eventsSlot: CapturingSlot<List<OutboxMessage>> = slot()
        every {
            batchRepo.settleWithEvents(capture(updatedSlot), capture(itemsSlot), capture(eventsSlot))
        } returns Uni.createFrom().item(savedBatch)

        val result = service.settleBatch(batchId).await().indefinitely()

        assertThat(result).isEqualTo(savedBatch)
        assertThat(updatedSlot.captured.status).isEqualTo(ClearingStatus.SETTLED)
        assertThat(updatedSlot.captured.settledAt).isNotNull()
        assertThat(itemsSlot.captured).allSatisfy { assertThat(it.status).isEqualTo(ClearingStatus.SETTLED) }
        // ADR-0281: the batch.settled event AND the net_settlement.post command commit together.
        assertThat(eventsSlot.captured).hasSize(2)
        verify { eventPublisher.batchSettledMessage(any()) }
        verify { eventPublisher.netSettlementPostMessage(any()) }
        verify { batchRepo.settleWithEvents(any(), any(), any()) }
    }

    @Test
    fun `settle batch throws on missing batch`() {
        val batchId = UUID.fromString("33333333-3333-3333-3333-333333333333")

        every { batchRepo.findById(batchId) } returns Uni.createFrom().nullItem()

        assertThatThrownBy { service.settleBatch(batchId).await().indefinitely() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Batch not found: $batchId")
        verify(exactly = 0) { batchRepo.settleWithEvents(any(), any(), any()) }
        verify(exactly = 0) { eventPublisher.batchSettledMessage(any()) }
    }

    @Test
    fun `reconcileBatch returns clean report when all items are settled`() {
        val batchId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val batch = ClearingBatch(
            id = batchId,
            batchReference = "BATCH-REC",
            rail = PaymentRail.SEPA_SCT,
            status = ClearingStatus.SETTLED,
            itemCount = 2,
            cycleId = "CYCLE-TEST",
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )
        val items = listOf(
            clearingItem(batchId = batchId, status = ClearingStatus.SETTLED),
            clearingItem(batchId = batchId, status = ClearingStatus.SETTLED),
        )
        every { batchRepo.findById(batchId) } returns Uni.createFrom().item(batch)
        every { itemRepo.findByBatchId(batchId) } returns Uni.createFrom().item(items)

        val report = service.reconcileBatch(batchId).await().indefinitely()

        assertThat(report.clean).isTrue()
        assertThat(report.settledItemCount).isEqualTo(2)
        assertThat(report.expectedItemCount).isEqualTo(2)
        assertThat(report.stuckItemIds).isEmpty()
    }

    @Test
    fun `reconcileBatch returns dirty report when items are stuck in IN_CLEARING`() {
        val batchId = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val batch = ClearingBatch(
            id = batchId,
            batchReference = "BATCH-STUCK",
            rail = PaymentRail.SEPA_SCT,
            status = ClearingStatus.SETTLED,
            itemCount = 3,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )
        val stuck = clearingItem(batchId = batchId, status = ClearingStatus.IN_CLEARING)
        val items = listOf(
            clearingItem(batchId = batchId, status = ClearingStatus.SETTLED),
            clearingItem(batchId = batchId, status = ClearingStatus.SETTLED),
            stuck,
        )
        every { batchRepo.findById(batchId) } returns Uni.createFrom().item(batch)
        every { itemRepo.findByBatchId(batchId) } returns Uni.createFrom().item(items)

        val report = service.reconcileBatch(batchId).await().indefinitely()

        assertThat(report.clean).isFalse()
        assertThat(report.settledItemCount).isEqualTo(2)
        assertThat(report.stuckItemIds).containsExactly(stuck.id)
    }

    @Test
    fun `triggerClearingCycle computes correct netPosition for bilateral settlement`() {
        val amount = BigDecimal("200.00")
        val items = listOf(
            clearingItem(amount = amount),
            clearingItem(amount = amount),
        )
        val batchSlot: CapturingSlot<ClearingBatch> = slot()

        every { itemRepo.findPendingByRail(PaymentRail.SEPA_SCT, any()) } returns Uni.createFrom().item(items)
        every { batchRepo.save(capture(batchSlot)) } answers {
            val b = firstArg<ClearingBatch>()
            Uni.createFrom().item(b)
        }
        every { itemRepo.saveAll(any()) } returns Uni.createFrom().item(items)

        service.triggerClearingCycle(PaymentRail.SEPA_SCT).await().indefinitely()

        val batch = batchSlot.captured
        assertThat(batch.totalDebit).isEqualByComparingTo(BigDecimal("400.00"))
        assertThat(batch.totalCredit).isEqualByComparingTo(BigDecimal("400.00"))
        assertThat(batch.netPosition).isEqualByComparingTo(BigDecimal.ZERO)
    }

    private fun clearingItem(
        batchId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000"),
        status: ClearingStatus = ClearingStatus.PENDING,
        amount: BigDecimal = BigDecimal("100.00"),
    ) = ClearingItem(
        batchId = batchId,
        paymentId = UUID.randomUUID(),
        paymentReference = "PAY-${UUID.randomUUID()}",
        debtorIban = "DE89370400440532013000",
        creditorIban = "DE12500105170648489890",
        amount = amount,
        currency = "EUR",
        status = status,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    private fun SubmitPaymentRequest.toExpectedItem(): ClearingItem = ClearingItem(
        batchId = UUID.fromString("00000000-0000-0000-0000-000000000000"),
        paymentId = paymentId,
        paymentReference = paymentReference,
        debtorIban = debtorIban,
        creditorIban = creditorIban,
        debtorBic = debtorBic,
        creditorBic = creditorBic,
        amount = amount,
        currency = currency,
        status = ClearingStatus.PENDING,
        valueDate = valueDate,
        endToEndId = endToEndId,
        remittanceInfo = remittanceInfo,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )
}
