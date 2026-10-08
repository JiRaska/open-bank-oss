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
import com.openbank.standingorder.infrastructure.persistence.mapper.toDomain
import com.openbank.standingorder.infrastructure.persistence.mapper.toEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
        val cmd = createCommand().copy(customerActorId = UUID.randomUUID())
        var stored: StandingOrder? = null
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } answers { stored }
        coEvery { repo.save(any()) } answers { firstArg<StandingOrder>().also { stored = it } }

        val first = service.create(cmd)
        val replay = service.create(cmd)

        assertThat(replay).isEqualTo(first)
        assertThat(first.requestFingerprint).hasSize(64)
        assertThat(first.customerActorId).isEqualTo(cmd.customerActorId)
        assertThat(first.toEntity().toDomain()).isEqualTo(first)
        coVerify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `create() rejects same key with changed payload party actor or replacement target`(): Unit = runBlocking {
        val cmd = createCommand().copy(customerActorId = UUID.randomUUID())
        var stored: StandingOrder? = null
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } answers { stored }
        coEvery { repo.save(any()) } answers { firstArg<StandingOrder>().also { stored = it } }
        service.create(cmd)

        val changed = listOf(
            cmd.copy(amountMinorUnits = cmd.amountMinorUnits + 1),
            cmd.copy(debitAccountId = UUID.randomUUID()),
            cmd.copy(partyId = UUID.randomUUID()),
            cmd.copy(customerActorId = UUID.randomUUID()),
            cmd.copy(replacesStandingOrderId = UUID.randomUUID()),
        )
        changed.forEach { request ->
            assertThatThrownBy { runBlocking { service.create(request) } }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Idempotency key is already bound to another request")
        }
        coVerify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `edge defaulted date has stable retry identity across midnight`(): Unit = runBlocking {
        val cmd = createCommand().copy(startDateDefaulted = true, customerActorId = UUID.randomUUID())
        var stored: StandingOrder? = null
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } answers { stored }
        coEvery { repo.save(any()) } answers { firstArg<StandingOrder>().also { stored = it } }

        val original = service.create(cmd)
        assertThat(service.create(cmd.copy(startDate = cmd.startDate.plusDays(1)))).isEqualTo(original)
        assertThatThrownBy { runBlocking { service.create(cmd.copy(startDateDefaulted = false)) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `unique-key loser reads the committed winner before answering replay or conflict`(): Unit = runBlocking {
        val cmd = createCommand().copy(customerActorId = UUID.randomUUID())
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } returns null
        coEvery { repo.save(any()) } answers { firstArg() }
        val winner = service.create(cmd)
        coEvery { repo.save(any()) } throws IllegalStateException("concurrent unique key")
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } returnsMany listOf(null, winner)

        assertThat(service.create(cmd)).isEqualTo(winner)

        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } returnsMany listOf(null, winner)
        assertThatThrownBy { runBlocking { service.create(cmd.copy(amountMinorUnits = 2_501)) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Idempotency key is already bound to another request")
    }

    @Test
    fun `legacy unbound row cannot be treated as a proven replay`(): Unit = runBlocking {
        val existing = standingOrder()
        val cmd = createCommand().copy(idempotencyKey = existing.idempotencyKey)
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } returns existing

        assertThatThrownBy { runBlocking { service.create(cmd) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Idempotency key is already bound to another request")
        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `receipt returns only a durable order bound to party account and original actor`(): Unit = runBlocking {
        val cmd = createCommand().copy(customerActorId = UUID.randomUUID())
        val actor = requireNotNull(cmd.customerActorId)
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } returns null
        coEvery { repo.save(any()) } answers { firstArg() }
        val stored = service.create(cmd)
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } returns stored

        assertThat(service.findBoundReceipt(cmd.idempotencyKey, cmd.partyId, cmd.debitAccountId, actor))
            .isEqualTo(stored)
        assertThat(service.findBoundReceipt(cmd.idempotencyKey, UUID.randomUUID(), cmd.debitAccountId, actor))
            .isNull()
        assertThat(service.findBoundReceipt(cmd.idempotencyKey, cmd.partyId, UUID.randomUUID(), actor))
            .isNull()
        assertThat(service.findBoundReceipt(cmd.idempotencyKey, cmd.partyId, cmd.debitAccountId, UUID.randomUUID()))
            .isNull()
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } returns stored.copy(requestFingerprint = null)
        assertThat(service.findBoundReceipt(cmd.idempotencyKey, cmd.partyId, cmd.debitAccountId, actor))
            .isNull()
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } returns null
        assertThat(service.findBoundReceipt(cmd.idempotencyKey, cmd.partyId, cmd.debitAccountId, actor))
            .isNull()
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
