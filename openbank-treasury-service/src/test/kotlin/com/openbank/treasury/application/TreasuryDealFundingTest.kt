// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.treasury.application.port.`in`.DraftDealCommand
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.FundingExceededException
import com.openbank.treasury.application.port.out.FundingMeter
import com.openbank.treasury.application.port.out.FundingPort
import com.openbank.treasury.application.usecase.TreasuryDealService
import com.openbank.treasury.domain.DealFixtures
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.infrastructure.LegWireJackson
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #12115: a placement books only against money the contra account verifiably holds — the balance,
 * net of every other committed (BOOKED/CONFIRMED) outflow. Exactly equal passes; one minor unit
 * above is refused; a concurrent booking that commits between the pre-check and the locked
 * re-measure is refused by the guard, with nothing committed.
 */
class TreasuryDealFundingTest {

    private val monday: LocalDate = DealFixtures.MONDAY
    private val clock: Clock = Clock.fixed(monday.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private val deals = InMemoryDeals()
    private val ledger = RecordingLedger()

    /** A high-limit counterparty: these tests exercise the funding gate, not the counterparty line. */
    private val bank = com.openbank.treasury.domain.model.Counterparty(
        id = "SIMBK-F",
        name = "Funding test bank (synthetic)",
        kind = com.openbank.treasury.domain.model.CounterpartyKind.BANK,
        limits = mapOf("CZK" to BigDecimal("100000000.00"), "EUR" to BigDecimal("100000000.00")),
        synthetic = true,
    )
    private val cps = object : CounterpartyRepository {
        override suspend fun findById(id: String) = bank.takeIf { it.id == id }
        override suspend fun list() = listOf(bank)
    }
    private val mapper = LegWireJackson.register(ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule()))

    /** What the ledger's balance route answers for the contra account. */
    private var balance = BigDecimal("1000000.00")
    private val balanceQueries = mutableListOf<Pair<String, LocalDate>>()
    private val metered = mutableListOf<String>()

    private val service
        get() = TreasuryDealService(
            deals,
            cps,
            ledger,
            mapper,
            clock,
            productLimits = DealFixtures.permissiveProductLimits,
            funding = FundingPort { ccy, asOf ->
                balanceQueries += ccy to asOf
                balance
            },
            fundingMeter = FundingMeter { metered += it },
        )

    private fun placement(principal: String, value: LocalDate = monday) = DraftDealCommand(
        ProductType.MM_PLACEMENT,
        "SIMBK-F",
        "CZK",
        BigDecimal(principal),
        BigDecimal("4.00"),
        null,
        value,
        value.plusDays(7),
        null,
    )

    private suspend fun book(c: DraftDealCommand) = service.let {
        val d = it.draft(c, DealFixtures.dealer)
        it.submit(d.id, DealFixtures.dealer)
        it.approve(d.id, DealFixtures.approver)
    }

    @Test
    fun `a placement exactly equal to the balance books`(): Unit = runBlocking {
        val booked = book(placement("1000000.00"))
        assertThat(booked.state).isEqualTo(DealState.BOOKED)
        assertThat(balanceQueries).isNotEmpty
        assertThat(metered).isEmpty()
    }

    @Test
    fun `a placement one minor unit above the balance is refused and nothing commits`(): Unit = runBlocking {
        val d = service.draft(placement("1000000.01"), DealFixtures.dealer)
        service.submit(d.id, DealFixtures.dealer)
        assertThatThrownBy { runBlocking { service.approve(d.id, DealFixtures.approver) } }
            .isInstanceOf(FundingExceededException::class.java)
            .hasMessageContaining("1000000.01")
        assertThat(service.get(d.id).deal.state).isEqualTo(DealState.PENDING_APPROVAL)
        assertThat(metered).containsExactly("CZK")
        assertThat(ledger.posted).isEmpty()
    }

    @Test
    fun `a committed outflow consumes the balance - 600k booked, then 400k passes and one more is refused`(): Unit =
        runBlocking {
            book(placement("600000.00"))
            assertThat(book(placement("400000.00")).state).isEqualTo(DealState.BOOKED)
            val d = service.draft(placement("0.01"), DealFixtures.dealer)
            service.submit(d.id, DealFixtures.dealer)
            assertThatThrownBy { runBlocking { service.approve(d.id, DealFixtures.approver) } }
                .isInstanceOf(FundingExceededException::class.java)
        }

    @Test
    fun `a settled placement is carried by the balance, not counted again`(): Unit = runBlocking {
        val settled = book(placement("600000.00"))
        service.confirm(settled.id, null, DealFixtures.approver)
        service.settle(settled.id, DealFixtures.approver)
        // The ledger now holds the 600k outflow: its balance answer drops accordingly.
        balance = BigDecimal("400000.00")
        assertThat(book(placement("400000.00")).state).isEqualTo(DealState.BOOKED)
    }

    @Test
    fun `a borrowing brings cash in and is never funding-checked`(): Unit = runBlocking {
        balance = BigDecimal.ZERO
        val borrowing = DraftDealCommand(
            ProductType.MM_BORROWING,
            "SIMBK-F",
            "CZK",
            BigDecimal("900000.00"),
            BigDecimal("5.00"),
            null,
            monday,
            monday.plusDays(7),
            null,
        )
        assertThat(book(borrowing).state).isEqualTo(DealState.BOOKED)
        assertThat(balanceQueries).isEmpty()
    }

    @Test
    fun `a booking that commits between the pre-check and the locked re-measure is refused by the guard`(): Unit =
        runBlocking {
            val d = service.draft(placement("600000.00"), DealFixtures.dealer)
            service.submit(d.id, DealFixtures.dealer)
            var raced = false
            deals.beforeGuardRecheck = {
                if (!raced) {
                    raced = true
                    runBlocking { book(placement("600000.00")) }
                }
            }
            // The pre-check sees no committed outflow (1M - 0 - 600k fits); the save's locked
            // re-measure finds the racing 600k committed and refuses (1M - 600k - 600k < 0).
            assertThatThrownBy { runBlocking { service.approve(d.id, DealFixtures.approver) } }
                .isInstanceOf(FundingExceededException::class.java)
            assertThat(service.get(d.id).deal.state).isEqualTo(DealState.PENDING_APPROVAL)
            assertThat(metered).containsExactly("CZK")
        }

    @Test
    fun `a nearer-dated booking counts a farther-dated committed outflow - A 800k at D+5 then B 800k at D+1 is refused`(): Unit =
        runBlocking {
            // A books 800k valued D+5; B (800k, valued D+1, BEFORE A) must still see A: both would
            // settle from the same 1M, leaving the contra account 600k short.
            assertThat(book(placement("800000.00", value = monday.plusDays(5))).state).isEqualTo(DealState.BOOKED)
            val b = service.draft(placement("800000.00", value = monday.plusDays(1)), DealFixtures.dealer)
            service.submit(b.id, DealFixtures.dealer)
            assertThatThrownBy { runBlocking { service.approve(b.id, DealFixtures.approver) } }
                .isInstanceOf(FundingExceededException::class.java)
            assertThat(service.get(b.id).deal.state).isEqualTo(DealState.PENDING_APPROVAL)
            assertThat(metered).containsExactly("CZK")
        }
}
