// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
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
    fun `settle batch transitions batch to settled and publishes event`() {
        val batchId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val batch = ClearingBatch(
            id = batchId,
            batchReference = "BATCH-002",
            rail = PaymentRail.SEPA_SCT,
            status = ClearingStatus.IN_CLEARING,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )
        val updatedSlot: CapturingSlot<ClearingBatch> = slot()
        val savedBatch = batch.copy(
            status = ClearingStatus.SETTLED,
            settledAt = OffsetDateTime.parse("2026-01-20T10:15:30Z"),
            updatedAt = OffsetDateTime.parse("2026-01-20T10:15:31Z"),
        )

        every { batchRepo.findById(batchId) } returns Uni.createFrom().item(batch)
        every { batchRepo.update(capture(updatedSlot)) } returns Uni.createFrom().item(savedBatch)
        every { eventPublisher.publishBatchSettled(savedBatch) } returns Uni.createFrom().voidItem()

        val result = service.settleBatch(batchId).await().indefinitely()

        assertThat(result).isEqualTo(savedBatch)
        assertThat(updatedSlot.captured.status).isEqualTo(ClearingStatus.SETTLED)
        assertThat(updatedSlot.captured.settledAt).isNotNull()
        assertThat(updatedSlot.captured.updatedAt).isNotNull()
        verifyOrder {
            batchRepo.findById(batchId)
            batchRepo.update(any())
            eventPublisher.publishBatchSettled(savedBatch)
        }
    }

    @Test
    fun `settle batch throws on missing batch`() {
        val batchId = UUID.fromString("33333333-3333-3333-3333-333333333333")

        every { batchRepo.findById(batchId) } returns Uni.createFrom().nullItem()

        assertThatThrownBy { service.settleBatch(batchId).await().indefinitely() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Batch not found: $batchId")
        verify(exactly = 0) { batchRepo.update(any()) }
        verify(exactly = 0) { eventPublisher.publishBatchSettled(any()) }
    }

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
