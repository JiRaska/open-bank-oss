// SPDX-License-Identifier: Apache-2.0
package com.openbank.balance.application.usecase

import com.openbank.balance.application.port.`in`.InitializeBalanceCommand
import com.openbank.balance.application.port.`in`.PlaceHoldCommand
import com.openbank.balance.application.port.out.BalanceMovementPort
import com.openbank.balance.application.port.out.BalanceRepository
import com.openbank.balance.application.port.out.HoldRepository
import com.openbank.balance.domain.model.Balance
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class BalanceServiceClockTest {

    private val balanceRepo: BalanceRepository = mockk()
    private val holdRepo: HoldRepository = mockk()
    private val movementPort: BalanceMovementPort = mockk()

    @Test
    fun `initializeBalance stamps updatedAt from injected clock`(): Unit = runBlocking {
        val fixedInstant = Instant.parse("2024-01-15T10:00:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val service = BalanceService(balanceRepo, holdRepo, movementPort, fixedClock)

        val accountId = UUID.randomUUID()
        coEvery { balanceRepo.findByAccountIdAndCurrency(accountId, "CZK") } returns null
        val saved = Balance(
            id = UUID.randomUUID(), accountId = accountId, currency = "CZK",
            bookedAmount = BigDecimal.ZERO, availableAmount = BigDecimal.ZERO,
            reservedAmount = BigDecimal.ZERO, pendingAmount = BigDecimal.ZERO,
            updatedAt = OffsetDateTime.now(fixedClock), version = 0,
        )
        coEvery { balanceRepo.save(any()) } returns saved

        val result = service.initializeBalance(
            InitializeBalanceCommand(accountId, "CZK", BigDecimal.ZERO, BigDecimal.ZERO),
        )

        assertThat(result.updatedAt).isEqualTo(OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC))
    }

    @Test
    fun `placeHold stamps hold timestamps from injected clock`(): Unit = runBlocking {
        val fixedInstant = Instant.parse("2024-06-01T12:00:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val service = BalanceService(balanceRepo, holdRepo, movementPort, fixedClock)

        val accountId = UUID.randomUUID()
        val balance = Balance(
            id = UUID.randomUUID(), accountId = accountId, currency = "CZK",
            bookedAmount = BigDecimal("1000"), availableAmount = BigDecimal("1000"),
            reservedAmount = BigDecimal.ZERO, pendingAmount = BigDecimal.ZERO,
            updatedAt = OffsetDateTime.now(fixedClock), version = 1,
        )
        coEvery { balanceRepo.findByAccountIdAndCurrency(accountId, "CZK") } returns balance
        // The cover decision reads the not-yet-effective credit tail (#1745); nothing is booked
        // forward here, so the value-date basis is a no-op and this test's subject is unaffected.
        coEvery { balanceRepo.sumNotYetEffectiveCredit(any(), any(), any()) } returns BigDecimal.ZERO
        // #8510: the reservation + hold + event go through ONE transactional repository method.
        coEvery { holdRepo.saveWithEvent(any(), any(), any()) } answers { firstArg() }

        val result = service.placeHold(
            PlaceHoldCommand(accountId, BigDecimal("100"), "CZK", "test", "ref1", null),
        )

        assertThat(result.createdAt).isEqualTo(OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC))
    }
}
