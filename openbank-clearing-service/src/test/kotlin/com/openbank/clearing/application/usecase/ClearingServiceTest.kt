// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.application.usecase

import com.openbank.clearing.application.port.out.ClearingBatchRepository
import com.openbank.clearing.application.port.out.ClearingCycleMetrics
import com.openbank.clearing.application.port.out.ClearingEventPublisher
import com.openbank.clearing.application.port.out.ClearingItemRepository
import com.openbank.clearing.application.port.out.SettlementPositionRepository
import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.clearing.domain.model.PaymentRail
import com.openbank.clearing.domain.model.SubmitPaymentCommand
import com.openbank.libs.domain.money.Money
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
    private val cycleMetrics = mockk<ClearingCycleMetrics>(relaxed = true)
    private val service = ClearingService(
        batchRepo,
        itemRepo,
        positionRepo,
        eventPublisher,
        fixedClock,
        { setOf("CZK", "EUR") },
        cycleMetrics,
    )

    init {
        every { itemRepo.countPendingWithoutRail() } returns Uni.createFrom().item(0L)
    }

    @Test
    fun `submit saves clearing item with pending status`() {
        val request = SubmitPaymentCommand(
            paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            paymentReference = "PAY-001",
            debtorIban = "DE89370400440532013000",
            creditorIban = "DE12500105170648489890",
            debtorBic = "DEUTDEFF",
            creditorBic = "COBADEFF",
            amount = Money.of("125.50", "EUR"),
            valueDate = LocalDate.of(2026, 1, 20),
            endToEndId = "E2E-001",
            remittanceInfo = "Invoice 42",
        )
        val savedItem = request.toExpectedItem()
        val itemSlot: CapturingSlot<ClearingItem> = slot()

        every { itemRepo.findByPaymentId(request.paymentId) } returns Uni.createFrom().item(emptyList())
        every { itemRepo.save(capture(itemSlot)) } returns Uni.createFrom().item(savedItem)

        val result = service.submit(request).await().indefinitely()

        assertThat(result).isEqualTo(savedItem)
        assertThat(itemSlot.captured.paymentId).isEqualTo(request.paymentId)
        assertThat(itemSlot.captured.paymentReference).isEqualTo(request.paymentReference)
        assertThat(itemSlot.captured.debtorIban).isEqualTo(request.debtorIban)
        assertThat(itemSlot.captured.creditorIban).isEqualTo(request.creditorIban)
        assertThat(itemSlot.captured.amount).isEqualTo(BigDecimal("125.50"))
        assertThat(itemSlot.captured.currency).isEqualTo("EUR")
        assertThat(itemSlot.captured.rail).isEqualTo(PaymentRail.SEPA_SCT)
        assertThat(itemSlot.captured.status).isEqualTo(ClearingStatus.PENDING)
        verify(exactly = 1) { itemRepo.save(any()) }
    }

    @Test
    fun `a retried submit for the same payment replays the existing clearing item`() {
        // ADR-0298 (#8351): a payment enters clearing exactly once — a retry must not stack a
        // second PENDING row that the clearing cycle would sweep into a batch and settle twice.
        val request = SubmitPaymentCommand(
            paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            paymentReference = "PAY-001",
            debtorIban = "DE89370400440532013000",
            creditorIban = "DE12500105170648489890",
            amount = Money.of("125.50", "EUR"),
        )
        val existing = request.toExpectedItem()

        every { itemRepo.findByPaymentId(request.paymentId) } returns Uni.createFrom().item(listOf(existing))

        val result = service.submit(request).await().indefinitely()

        assertThat(result).isEqualTo(existing)
        verify(exactly = 0) { itemRepo.save(any()) }
    }

    @Test
    fun `a submit that loses the unique-index race re-reads the winner`() {
        // ADR-0298 (#8351): uq_clearing_items_payment (V9) fires on a true-concurrency race; the
        // loser replays the winner instead of erroring the caller.
        val request = SubmitPaymentCommand(
            paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            paymentReference = "PAY-001",
            debtorIban = "DE89370400440532013000",
            creditorIban = "DE12500105170648489890",
            amount = Money.of("125.50", "EUR"),
        )
        val winner = request.toExpectedItem()
        val violation = java.sql.SQLException(
            "duplicate key value violates unique constraint \"uq_clearing_items_payment\" (23505)",
            "23505",
        )

        every { itemRepo.findByPaymentId(request.paymentId) } returnsMany
            listOf(
                Uni.createFrom().item(emptyList()),
                Uni.createFrom().item(listOf(winner)),
            )
        every { itemRepo.save(any()) } returns Uni.createFrom().failure(violation)

        val result = service.submit(request).await().indefinitely()

        assertThat(result).isEqualTo(winner)
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
        every { eventPublisher.itemClearedMessage(any()) } returns mockk()
        val eventsSlot: CapturingSlot<List<OutboxMessage>> = slot()
        every {
            batchRepo.settleWithEvents(capture(updatedSlot), capture(itemsSlot), capture(eventsSlot))
        } returns Uni.createFrom().item(savedBatch)

        val result = service.settleBatch(batchId).await().indefinitely()

        assertThat(result).isEqualTo(savedBatch)
        assertThat(updatedSlot.captured.status).isEqualTo(ClearingStatus.SETTLED)
        assertThat(updatedSlot.captured.settledAt).isNotNull()
        assertThat(itemsSlot.captured).allSatisfy { assertThat(it.status).isEqualTo(ClearingStatus.SETTLED) }
        // Batch, ledger intent and one source-versioned acknowledgement per item commit together.
        assertThat(eventsSlot.captured).hasSize(4)
        assertThat(itemsSlot.captured.map { it.revision }).containsOnly(1)
        verify { eventPublisher.batchSettledMessage(any()) }
        verify { eventPublisher.netSettlementPostMessage(any()) }
        verify(exactly = 2) { eventPublisher.itemClearedMessage(any()) }
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
    fun `an empty cycle announces its settlement`() {
        val batchSlot: CapturingSlot<ClearingBatch> = slot()
        val eventSlot: CapturingSlot<OutboxMessage> = slot()
        val settledMessage = mockk<OutboxMessage>()

        every { itemRepo.countPendingOutside(PaymentRail.SEPA_SCT_INST, any()) } returns
            Uni.createFrom().item(emptyMap())
        every { itemRepo.findPendingByRail(PaymentRail.SEPA_SCT_INST, any(), any()) } returns
            Uni.createFrom().item(emptyList())
        every { eventPublisher.batchSettledMessage(capture(batchSlot)) } returns settledMessage
        every { batchRepo.saveWithEvent(any(), capture(eventSlot)) } answers {
            Uni.createFrom().item(firstArg<ClearingBatch>())
        }

        val batch = service.triggerClearingCycle(PaymentRail.SEPA_SCT_INST).await().indefinitely().batches.single()

        // The cycle RAN. Without an event a consumer cannot tell that from "the cycle did not
        // run" -- distinguishing those two is why this event exists.
        assertThat(batch.status).isEqualTo(ClearingStatus.SETTLED)
        assertThat(batch.itemCount).isEqualTo(0)
        assertThat(batch.cycleId!!.length).isLessThanOrEqualTo(32)
        assertThat(eventSlot.captured).isSameAs(settledMessage)
        assertThat(batchSlot.captured.status).isEqualTo(ClearingStatus.SETTLED)

        // Announced atomically with the insert, never through the bare save that wrote no event.
        verify(exactly = 1) { batchRepo.saveWithEvent(any(), any()) }
        verify(exactly = 0) { batchRepo.save(any()) }
    }

    @Test
    fun `triggerClearingCycle computes correct netPosition for bilateral settlement`() {
        val amount = BigDecimal("200.00")
        val items = listOf(
            clearingItem(amount = amount),
            clearingItem(amount = amount),
        )
        val batchSlot: CapturingSlot<ClearingBatch> = slot()

        every { itemRepo.countPendingOutside(PaymentRail.SEPA_SCT, any()) } returns Uni.createFrom().item(emptyMap())
        every { itemRepo.findPendingByRail(PaymentRail.SEPA_SCT, any(), any()) } returns Uni.createFrom().item(items)
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

    @Test
    fun `a cycle opens one batch per currency and never adds across currencies`() {
        val items = listOf(
            clearingItem(amount = BigDecimal("100.00"), currency = "EUR"),
            clearingItem(amount = BigDecimal("1000.00"), currency = "CZK"),
            clearingItem(amount = BigDecimal("0.50"), currency = "EUR"),
        )
        val saved = mutableListOf<ClearingBatch>()
        every { itemRepo.countPendingOutside(PaymentRail.SEPA_SCT, setOf("CZK", "EUR")) } returns
            Uni.createFrom().item(emptyMap())
        every { itemRepo.findPendingByRail(PaymentRail.SEPA_SCT, setOf("CZK", "EUR"), any()) } returns
            Uni.createFrom().item(items)
        every { batchRepo.save(capture(saved)) } answers { Uni.createFrom().item(firstArg<ClearingBatch>()) }
        every { itemRepo.saveAll(any()) } answers { Uni.createFrom().item(firstArg<List<ClearingItem>>()) }

        val result = service.triggerClearingCycle(PaymentRail.SEPA_SCT).await().indefinitely()

        assertThat(result.batches.map { it.currency }).containsExactly("CZK", "EUR")
        val byCcy = result.batches.associateBy { it.currency }
        assertThat(byCcy.getValue("CZK").totalDebit).isEqualByComparingTo("1000.00")
        assertThat(byCcy.getValue("CZK").itemCount).isEqualTo(1)
        assertThat(byCcy.getValue("EUR").totalDebit).isEqualByComparingTo("100.50")
        assertThat(byCcy.getValue("EUR").itemCount).isEqualTo(2)
        assertThat(result.batches.map { it.batchReference }).doesNotHaveDuplicates()
        assertThat(result.batches.map { it.cycleId }.distinct()).containsExactly(result.cycleId)
        assertThat(result.cycleId.length).isLessThanOrEqualTo(32)
        // Each item is re-homed to the batch of its own currency.
        verify {
            itemRepo.saveAll(match { l -> l.all { it.currency == "EUR" && it.batchId == byCcy.getValue("EUR").id } })
        }
        verify {
            itemRepo.saveAll(match { l -> l.all { it.currency == "CZK" && it.batchId == byCcy.getValue("CZK").id } })
        }
    }

    @Test
    fun `a currency with no settlement account is reported, not selected`() {
        every { itemRepo.countPendingOutside(PaymentRail.SEPA_SCT, setOf("CZK", "EUR")) } returns
            Uni.createFrom().item(mapOf("PLN" to 3L))
        every { itemRepo.findPendingByRail(PaymentRail.SEPA_SCT, setOf("CZK", "EUR"), any()) } returns
            Uni.createFrom().item(emptyList())
        every { eventPublisher.batchSettledMessage(any()) } returns mockk()
        every { batchRepo.saveWithEvent(any(), any()) } answers { Uni.createFrom().item(firstArg<ClearingBatch>()) }

        val result = service.triggerClearingCycle(PaymentRail.SEPA_SCT).await().indefinitely()

        assertThat(result.unsettleablePending).containsEntry("PLN", 3L)
        verify(exactly = 1) { cycleMetrics.recordUnsettleablePending(mapOf("PLN" to 3L)) }
        verify(exactly = 0) { batchRepo.save(any()) }
    }

    @Test
    fun `clearing halts when legacy pending items have no verifiable rail`() {
        every { itemRepo.countPendingWithoutRail() } returns Uni.createFrom().item(1L)

        assertThatThrownBy { service.triggerClearingCycle(PaymentRail.SEPA_SCT).await().indefinitely() }
            .hasMessageContaining("legacy pending items have no verified payment rail")
        verify(exactly = 0) { itemRepo.findPendingByRail(any(), any(), any()) }
        verify(exactly = 0) { batchRepo.save(any()) }
    }

    private fun clearingItem(
        batchId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000"),
        status: ClearingStatus = ClearingStatus.PENDING,
        amount: BigDecimal = BigDecimal("100.00"),
        currency: String = "EUR",
    ) = ClearingItem(
        batchId = batchId,
        paymentId = UUID.randomUUID(),
        paymentReference = "PAY-${UUID.randomUUID()}",
        debtorIban = "DE89370400440532013000",
        creditorIban = "DE12500105170648489890",
        amount = amount,
        currency = currency,
        status = status,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    private fun SubmitPaymentCommand.toExpectedItem(): ClearingItem = ClearingItem(
        batchId = UUID.fromString("00000000-0000-0000-0000-000000000000"),
        paymentId = paymentId,
        paymentReference = paymentReference,
        debtorIban = debtorIban,
        creditorIban = creditorIban,
        debtorBic = debtorBic,
        creditorBic = creditorBic,
        amount = amount.amount,
        currency = amount.currency.code,
        rail = rail,
        status = ClearingStatus.PENDING,
        valueDate = valueDate,
        endToEndId = endToEndId,
        remittanceInfo = remittanceInfo,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )
}
