// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.fund

import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ProviderType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Exercise the production adapter itself: its contract lookup must not select a scheduled election. */
class PensionFundRestAdapterTest {
    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val today = LocalDate.parse("2026-10-09")
    private val currentFund = UUID.randomUUID()
    private val futureFund = UUID.randomUUID()
    private val repository = mockk<PensionContractRepository>()
    private val client = mockk<PensionFundRestClient>()
    private val placedOrders = mutableListOf<OrderRequestDto>()
    private val contract = PensionContract.draft(
        participantPartyId = UUID.randomUUID(),
        productLine = ProductLine.DPS,
        jurisdiction = "CZ",
        packVersion = 1,
        providerEntityId = UUID.randomUUID(),
        providerType = ProviderType.PENSION_COMPANY,
        participantBirthDate = LocalDate.parse("1990-01-01"),
        schedule = ContributionSchedule(BigDecimal("1700"), "CZK", ContributionFrequency.MONTHLY),
        initialStrategy = "CONSERVATIVE",
        beneficiaries = emptyList(),
        today = today,
        now = now,
    ).submit(now).activate(today, now).electStrategy("DYNAMIC", today.plusDays(1), now.plusSeconds(1))

    private fun adapter(asOf: Instant): PensionFundRestAdapter {
        coEvery { repository.findById(contract.id) } returns contract
        coEvery { client.strategies() } returns listOf(
            StrategyDto(
                UUID.randomUUID(),
                "CONSERVATIVE",
                "ACTIVE",
                listOf(AllocationTargetDto(currentFund, BigDecimal.ONE)),
            ),
            StrategyDto(
                UUID.randomUUID(),
                "DYNAMIC",
                "ACTIVE",
                listOf(AllocationTargetDto(futureFund, BigDecimal.ONE)),
            ),
        )
        coEvery { client.placeOrder(contract.id, any(), capture(placedOrders)) } returns
            UnitOrderDto(UUID.randomUUID(), "PENDING")
        return PensionFundRestAdapter().also {
            it.contracts = repository
            it.client = client
            it.clock = Clock.fixed(asOf, ZoneOffset.UTC)
        }
    }

    @Test
    fun `today contribution uses current strategy even when a future election exists`(): Unit = runBlocking {
        adapter(now).subscribe(contract.id, BigDecimal("1700"), "CZK", "contribution-today")
        assertThat(placedOrders).hasSize(1)
        assertThat(placedOrders.single().fundId).isEqualTo(currentFund)
        assertThat(placedOrders.single().amount).isEqualByComparingTo("1700")
    }

    @Test
    fun `scheduled strategy starts at midnight of the clock business date`(): Unit = runBlocking {
        adapter(Instant.parse("2026-10-10T00:00:00Z"))
            .subscribe(contract.id, BigDecimal("1700"), "CZK", "contribution-tomorrow")
        assertThat(placedOrders).hasSize(1)
        assertThat(placedOrders.single().fundId).isEqualTo(futureFund)
    }

    @Test
    fun `no effective election refuses before placing any order`() {
        val adapter = adapter(Instant.parse("2026-10-08T23:59:59Z"))
        assertThatThrownBy {
            runBlocking { adapter.subscribe(contract.id, BigDecimal("1700"), "CZK", "too-early") }
        }.isInstanceOf(FundAdministrationRefusedException::class.java)
        coVerify(exactly = 0) { client.placeOrder(any(), any(), any()) }
        assertThat(placedOrders).isEmpty()
    }
}
