// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.valuation

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.FundHolding
import com.openbank.pension.application.port.out.FundHoldings
import com.openbank.pension.application.port.out.FundUnitTransaction
import com.openbank.pension.application.port.out.PendingFundOrder
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.usecase.ContractValuationService
import com.openbank.pension.application.usecase.ValuationStatus
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The valuation view's honesty rules and its ownership rule, against a fake register. */
class ContractValuationServiceTest {

    private val owner = UUID.randomUUID()
    private val contractId = UUID.randomUUID()
    private val fundA = UUID.randomUUID()
    private val fundB = UUID.randomUUID()

    private val contract = mockk<PensionContract> {
        every { id } returns contractId
        every { participantPartyId } returns owner
        every { schedule } returns ContributionSchedule(BigDecimal("1700"), "CZK", ContributionFrequency.MONTHLY)
    }
    private val contracts = mockk<PensionContractRepository> { coEvery { findById(contractId) } returns contract }
    private val fund = mockk<FundAdministrationPort>()
    private val service = ContractValuationService(contracts, fund)

    private fun priced(f: UUID, value: String, date: String) =
        FundHolding(f, BigDecimal("10"), BigDecimal("1.5"), LocalDate.parse(date), BigDecimal(value), "CZK")

    private val pending =
        PendingFundOrder(UUID.randomUUID(), fundA, "SUBSCRIBE", BigDecimal("500"), null, Instant.EPOCH)

    @Test
    fun `every holding priced - the total is the sum and asOf is the latest NAV date`(): Unit = runBlocking {
        coEvery { fund.holdings(contractId) } returns FundHoldings(
            listOf(priced(fundA, "100.005", "2026-10-01"), priced(fundB, "50", "2026-10-02")),
            listOf(pending),
        )
        val view = service.valuation(Caller.customer(owner), contractId)
        assertThat(view.status).isEqualTo(ValuationStatus.VALUED)
        assertThat(view.totalValue).isEqualByComparingTo("150.00")
        assertThat(view.asOf).isEqualTo(LocalDate.parse("2026-10-02"))
        assertThat(view.pendingOrders).containsExactly(pending)
    }

    @Test
    fun `a fund with no published NAV - no total is stated, the holding is shown unpriced`(): Unit = runBlocking {
        coEvery { fund.holdings(contractId) } returns FundHoldings(
            listOf(priced(fundA, "100", "2026-10-01"), FundHolding(fundB, BigDecimal("4"), null, null, null, "CZK")),
            emptyList(),
        )
        val view = service.valuation(Caller.STAFF, contractId)
        assertThat(view.status).isEqualTo(ValuationStatus.NAV_NOT_PUBLISHED)
        assertThat(view.totalValue).isNull()
        assertThat(view.holdings.single { it.fundId == fundB }.value).isNull()
    }

    @Test
    fun `a holding in another currency - no total, never an FX guess`(): Unit = runBlocking {
        coEvery { fund.holdings(contractId) } returns FundHoldings(
            listOf(priced(fundA, "100", "2026-10-01").copy(currency = "EUR")),
            emptyList(),
        )
        val view = service.valuation(Caller.STAFF, contractId)
        assertThat(view.status).isEqualTo(ValuationStatus.CURRENCY_MISMATCH)
        assertThat(view.totalValue).isNull()
    }

    @Test
    fun `no units - zero, with pending orders still shown`(): Unit = runBlocking {
        coEvery { fund.holdings(contractId) } returns FundHoldings(
            listOf(FundHolding(fundA, BigDecimal.ZERO, null, null, null, "CZK")),
            listOf(pending),
        )
        val view = service.valuation(Caller.customer(owner), contractId)
        assertThat(view.status).isEqualTo(ValuationStatus.NO_HOLDINGS)
        assertThat(view.totalValue).isEqualByComparingTo("0")
        assertThat(view.holdings).isEmpty()
        assertThat(view.pendingOrders).hasSize(1)
    }

    @Test
    fun `someone else's contract is NOT FOUND and the register is never asked`() {
        assertThatThrownBy { runBlocking { service.valuation(Caller.customer(UUID.randomUUID()), contractId) } }
            .isInstanceOf(ContractNotFoundException::class.java)
        assertThatThrownBy {
            runBlocking { service.transactions(Caller.customer(UUID.randomUUID()), contractId, 0, 10) }
        }
            .isInstanceOf(ContractNotFoundException::class.java)
        coVerify(exactly = 0) { fund.holdings(any()) }
        coVerify(exactly = 0) { fund.transactions(any()) }
    }

    @Test
    fun `an unknown contract is NOT FOUND`() {
        val unknown = UUID.randomUUID()
        coEvery { contracts.findById(unknown) } returns null
        assertThatThrownBy { runBlocking { service.valuation(Caller.STAFF, unknown) } }
            .isInstanceOf(ContractNotFoundException::class.java)
    }

    @Test
    fun `transactions are newest first and paginated`(): Unit = runBlocking {
        val txs = (1..5).map { i ->
            FundUnitTransaction(
                UUID.randomUUID(),
                fundA,
                "SUBSCRIBE",
                BigDecimal(i),
                BigDecimal(i),
                BigDecimal.ONE,
                null,
                Instant.parse("2026-10-0${i}T10:00:00Z"),
            )
        }
        coEvery { fund.transactions(contractId) } returns txs
        val first = service.transactions(Caller.customer(owner), contractId, 0, 2)
        assertThat(first.total).isEqualTo(5)
        assertThat(first.items.map { it.pricedAt.toString() })
            .containsExactly("2026-10-05T10:00:00Z", "2026-10-04T10:00:00Z")
        assertThat(service.transactions(Caller.STAFF, contractId, 2, 2).items).hasSize(1)
        assertThat(service.transactions(Caller.STAFF, contractId, 9, 2).items).isEmpty()
    }

    @Test
    fun `page bounds are a 400, not a silent clamp`() {
        assertThatThrownBy { runBlocking { service.transactions(Caller.STAFF, contractId, -1, 10) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { runBlocking { service.transactions(Caller.STAFF, contractId, 0, 201) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
