// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.standingorder.application.port.`in`.CreateStandingOrderCommand
import com.openbank.standingorder.application.port.out.StandingOrderRepository
import com.openbank.standingorder.domain.model.Frequency
import com.openbank.standingorder.domain.model.PaymentType
import com.openbank.standingorder.domain.model.StandingOrder
import com.openbank.standingorder.domain.model.StandingOrderStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class StandingOrderServiceTest {

    private val repo: StandingOrderRepository = mockk()
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())
    private val service = StandingOrderService(repo, Clock.fixed(FIXED_NOW, java.time.ZoneOffset.UTC), mapper)

    @Test
    fun `create() is idempotent`(): Unit = runBlocking {
        val existing = standingOrder()
        val cmd = createCommand()
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } returns existing

        val result = service.create(cmd)

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `pause() saves with PAUSED status`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val order = standingOrder(id = id, status = StandingOrderStatus.ACTIVE)
        coEvery { repo.findById(id) } returns order
        val saved = slot<StandingOrder>()
        coEvery { repo.save(capture(saved)) } answers { saved.captured }

        val result = service.pause(id, "operator-1")

        assertThat(result.status).isEqualTo(StandingOrderStatus.PAUSED)
        assertThat(saved.captured.status).isEqualTo(StandingOrderStatus.PAUSED)
        coVerify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `cancel() saves with CANCELLED status`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000102")
        val order = standingOrder(id = id, status = StandingOrderStatus.ACTIVE)
        coEvery { repo.findById(id) } returns order
        val saved = slot<StandingOrder>()
        coEvery { repo.save(capture(saved)) } answers { saved.captured }

        val result = service.cancel(id, "operator-1")

        assertThat(result.status).isEqualTo(StandingOrderStatus.CANCELLED)
        assertThat(saved.captured.status).isEqualTo(StandingOrderStatus.CANCELLED)
        coVerify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `executeOrders() returns 0 when nothing is due`(): Unit = runBlocking {
        coEvery { repo.findDueForExecution(any()) } returns emptyList()

        val count = service.executeOrders(LocalDate.of(2026, 6, 26))

        assertThat(count).isEqualTo(0)
        coVerify(exactly = 0) { repo.saveWithExecution(any(), any()) }
    }

    @Test
    fun `executeOrders() persists execution and outbox event for each due order`(): Unit = runBlocking {
        val order = standingOrder(
            status = StandingOrderStatus.ACTIVE,
            nextExecutionDate = LocalDate.of(2026, 6, 26),
        )
        coEvery { repo.findDueForExecution(any()) } returns listOf(order)
        val savedOrder = slot<StandingOrder>()
        val savedMsg = slot<OutboxMessage>()
        coEvery { repo.saveWithExecution(capture(savedOrder), capture(savedMsg)) } answers { savedOrder.captured }

        val count = service.executeOrders(LocalDate.of(2026, 6, 26))

        assertThat(count).isEqualTo(1)
        assertThat(savedOrder.captured.executionCount).isEqualTo(order.executionCount + 1)
        assertThat(savedMsg.captured.eventType).isEqualTo(StandingOrderService.EVENT_STANDING_ORDER_DUE)
        coVerify(exactly = 1) { repo.saveWithExecution(any(), any()) }
    }

    @Test
    fun `recordFailure() increments failureCount and saves`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000501")
        val order = standingOrder(id = id, status = StandingOrderStatus.ACTIVE)
        coEvery { repo.findById(id) } returns order
        val saved = slot<StandingOrder>()
        coEvery { repo.save(capture(saved)) } answers { saved.captured }

        val result = service.recordFailure(id)

        assertThat(result.failureCount).isEqualTo(1)
        assertThat(result.status).isEqualTo(StandingOrderStatus.ACTIVE)
    }

    @Test
    fun `recordFailure() transitions to FAILED and emits outbox event after 3 failures`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000502")
        val order = standingOrder(id = id, status = StandingOrderStatus.ACTIVE, failureCount = 2)
        coEvery { repo.findById(id) } returns order
        val savedOrder = slot<StandingOrder>()
        coEvery { repo.saveWithExecution(capture(savedOrder), any()) } answers { savedOrder.captured }

        val result = service.recordFailure(id)

        assertThat(result.failureCount).isEqualTo(3)
        assertThat(result.status).isEqualTo(StandingOrderStatus.FAILED)
        coVerify(exactly = 1) { repo.saveWithExecution(any(), any()) }
    }

    @Test
    fun `confirmExecution() resets failureCount to zero`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000503")
        val order = standingOrder(id = id, status = StandingOrderStatus.ACTIVE, failureCount = 2)
        coEvery { repo.findById(id) } returns order
        val saved = slot<StandingOrder>()
        coEvery { repo.save(capture(saved)) } answers { saved.captured }

        val result = service.confirmExecution(id)

        assertThat(result.failureCount).isEqualTo(0)
        coVerify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `confirmExecution() is a no-op when failureCount is already 0`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000504")
        val order = standingOrder(id = id, status = StandingOrderStatus.ACTIVE, failureCount = 0)
        coEvery { repo.findById(id) } returns order

        val result = service.confirmExecution(id)

        assertThat(result.failureCount).isEqualTo(0)
        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `executeOrders() skips failing orders and continues processing remaining ones`(): Unit = runBlocking {
        val order1 = standingOrder(
            id = UUID.fromString("00000000-0000-0000-0000-000000000401"),
            nextExecutionDate = LocalDate.of(2026, 6, 26),
        )
        val order2 = standingOrder(
            id = UUID.fromString("00000000-0000-0000-0000-000000000402"),
            nextExecutionDate = LocalDate.of(2026, 6, 26),
        )
        coEvery { repo.findDueForExecution(any()) } returns listOf(order1, order2)
        coEvery { repo.saveWithExecution(match { it.id == order1.id }, any()) } throws RuntimeException("db error")
        coEvery { repo.saveWithExecution(match { it.id == order2.id }, any()) } answers { firstArg() }

        val count = service.executeOrders(LocalDate.of(2026, 6, 26))

        assertThat(count).isEqualTo(1)
        coVerify(exactly = 2) { repo.saveWithExecution(any(), any()) }
    }

    private fun createCommand() = CreateStandingOrderCommand(
        idempotencyKey = "idem-1",
        partyId = UUID.fromString("00000000-0000-0000-0000-000000000201"),
        debitAccountId = UUID.fromString("00000000-0000-0000-0000-000000000202"),
        debtorIban = "DE89370400440532013001",
        debtorName = "Debtor",
        creditorIban = "DE89370400440532013000",
        creditorName = "Creditor",
        creditorBic = "DEUTDEFF",
        amountMinorUnits = 2500L,
        currency = "EUR",
        frequency = Frequency.MONTHLY,
        paymentType = PaymentType.SEPA_CREDIT,
        remittanceInfo = "Rent",
        startDate = LocalDate.of(2026, 2, 1),
        endDate = LocalDate.of(2026, 12, 31),
    )

    private fun standingOrder(
        id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000301"),
        status: StandingOrderStatus = StandingOrderStatus.ACTIVE,
        nextExecutionDate: LocalDate = LocalDate.of(2026, 2, 1),
        failureCount: Int = 0,
    ) = StandingOrder(
        id = id,
        idempotencyKey = "idem-existing",
        partyId = UUID.fromString("00000000-0000-0000-0000-000000000302"),
        debitAccountId = UUID.fromString("00000000-0000-0000-0000-000000000303"),
        debtorIban = "DE89370400440532013001",
        debtorName = "Debtor",
        creditorIban = "DE89370400440532013000",
        creditorName = "Creditor",
        creditorBic = "DEUTDEFF",
        amountMinorUnits = 2500L,
        currency = "EUR",
        frequency = Frequency.MONTHLY,
        paymentType = PaymentType.SEPA_CREDIT,
        remittanceInfo = "Rent",
        startDate = LocalDate.of(2026, 2, 1),
        endDate = LocalDate.of(2026, 12, 31),
        nextExecutionDate = nextExecutionDate,
        lastExecutionDate = null,
        executionCount = 0,
        failureCount = failureCount,
        status = status,
        createdAt = FIXED_NOW,
        updatedAt = FIXED_NOW,
    )

    companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-01-15T10:15:30Z")
    }
}
