// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.treasury.application.port.`in`.DraftDealCommand
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.FxMidRatePort
import com.openbank.treasury.application.port.out.FxRateTolerance
import com.openbank.treasury.application.usecase.TreasuryDealService
import com.openbank.treasury.domain.DealFixtures
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.LedgerRole
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingLine
import com.openbank.treasury.domain.model.PostingRules
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.Side
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * #13411 (follow-up to #13205, ADR-0341 D3): a reversal offsets, per GL account, exactly what the
 * deal's journals posted to that account, and refuses — never re-resolves — a journal whose accounts
 * were not recorded or do not match its legs.
 */
class TreasuryReversalPinningTest {

    private var clock: Clock = Clock.fixed(DealFixtures.MONDAY.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val deals = InMemoryDeals()
    private val ledger = RecordingLedger()
    private val cps = object : CounterpartyRepository {
        override suspend fun findById(id: String) = listOf(DealFixtures.bankA).find { it.id == id }
        override suspend fun list() = listOf(DealFixtures.bankA)
    }
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val service get() = TreasuryDealService(
        deals, cps, ledger, mapper, clock, FxMidRatePort.NONE, FxRateTolerance.DISABLED, true, null,
        DealFixtures.permissiveProductLimits,
    )

    private suspend fun book(): Deal {
        val c = DraftDealCommand(
            ProductType.MM_PLACEMENT, "SIMBK-A", "CZK", BigDecimal("100000.00"), BigDecimal("4.00"), null,
            DealFixtures.MONDAY, DealFixtures.MONDAY.plusDays(7), null,
        )
        val d = service.draft(c, DealFixtures.dealer)
        service.submit(d.id, DealFixtures.dealer)
        return service.approve(d.id, DealFixtures.approver)
    }

    private fun key(l: PostingLine) = Triple(l.accountingClass, l.role, l.currency)

    @Test
    fun `accruals split across a re-pointed map are unwound per account, each by what it received`(): Unit =
        runBlocking {
            val b = book()
            service.runSimulatedMarket()
            clock = Clock.offset(clock, java.time.Duration.ofDays(2))
            service.accrueInterest(LocalDate.now(clock)) // days 1-2 land on account A (generation 1)
            ledger.mapGeneration = 2
            clock = Clock.offset(clock, java.time.Duration.ofDays(2))
            service.accrueInterest(LocalDate.now(clock)) // days 3-4 land on account B (generation 2)
            val accruals = ledger.posted.filter { it.event == PostingEvent.ACCRUED }
            assertThat(accruals).hasSize(4)
            val receivable = accruals.first().lines.single { it.role == LedgerRole.ACCRUED_INTEREST }
            val onA = accruals.take(2).sumOf { it.lines.first().amount }
            val onB = accruals.drop(2).sumOf { it.lines.first().amount }
            val accountA = UUID.nameUUIDFromBytes("${receivable.accountingClass}|ACCRUED_INTEREST|CZK|g1".toByteArray())
            val accountB = UUID.nameUUIDFromBytes("${receivable.accountingClass}|ACCRUED_INTEREST|CZK|g2".toByteArray())

            ledger.mapGeneration = 3 // and re-pointed again before the reversal: neither A nor B is current
            service.reverse(b.id, "test", DealFixtures.approver)

            val reversal = ledger.posted.single { it.event == PostingEvent.REVERSED }
            val unwind = reversal.lines.filter { it.role == LedgerRole.ACCRUED_INTEREST }
            assertThat(unwind.associate { it.glAccountId to it.amount })
                .describedAs("one line per account, each carrying what that account received")
                .hasSize(2)
                .containsKeys(accountA, accountB)
            assertThat(unwind.single { it.glAccountId == accountA }.amount).isEqualByComparingTo(onA)
            assertThat(unwind.single { it.glAccountId == accountB }.amount).isEqualByComparingTo(onB)
            assertThat(unwind).allMatch { it.side == Side.CREDIT }
            val income = reversal.lines.filter { it.role == LedgerRole.INTEREST_INCOME }
            assertThat(income).hasSize(2)
            assertThat(income.sumOf { it.amount }).isEqualByComparingTo(onA + onB)
        }

    @Test
    fun `a reversed journal with no recorded accounts is refused and nothing is posted`(): Unit = runBlocking {
        val b = book()
        service.runSimulatedMarket()
        clock = Clock.offset(clock, java.time.Duration.ofDays(2))
        service.accrueInterest(LocalDate.now(clock))
        // A journal posted before accounts were recorded (V15 NULL), or one whose answer could not be paired.
        val i = deals.journals.indexOfFirst { it.dealId == b.id && it.event == PostingEvent.ACCRUED }
        deals.journals[i] = deals.journals[i].copy(accounts = emptyList())
        val postedBefore = ledger.posted.size

        assertThatThrownBy { runBlocking { service.reverse(b.id, "test", DealFixtures.approver) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no recorded accounts")
        assertThat(ledger.posted).hasSize(postedBefore)
        assertThat(deals.findById(b.id)!!.state).isEqualTo(DealState.SETTLED)
    }

    @Test
    fun `a reversed journal whose recorded accounts do not match its lines is refused`(): Unit = runBlocking {
        val b = book()
        service.runSimulatedMarket()
        val i = deals.journals.indexOfFirst { it.dealId == b.id && it.event == PostingEvent.SETTLED }
        val recorded = deals.journals[i].accounts
        // The recorded legs name a role the settlement never posted: the record cannot be trusted.
        deals.journals[i] = deals.journals[i].copy(
            accounts = recorded.mapIndexed { n, a -> if (n == 0) a.copy(role = LedgerRole.INTEREST_INCOME) else a },
        )
        val postedBefore = ledger.posted.size

        assertThatThrownBy { runBlocking { service.reverse(b.id, "test", DealFixtures.approver) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("do not match")
        assertThat(ledger.posted).hasSize(postedBefore)
        assertThat(deals.findById(b.id)!!.state).isEqualTo(DealState.SETTLED)
    }

    @Test
    fun `with the map unchanged the reversal is the posting-rule reversal pinned to the posted accounts`(): Unit =
        runBlocking {
            val b = book()
            service.runSimulatedMarket()
            clock = Clock.offset(clock, java.time.Duration.ofDays(3))
            service.accrueInterest(LocalDate.now(clock))
            val deal = deals.findById(b.id)!!
            val accrued = PostingRules.accruedThrough(deal, LocalDate.now(clock))
            val expected = PostingRules.reversal(deal, DealState.SETTLED, accrued)!!.lines
                .map { it.copy(glAccountId = ledger.resolve(it)) }

            service.reverse(b.id, "test", DealFixtures.approver)

            val reversal = ledger.posted.single { it.event == PostingEvent.REVERSED }
            assertThat(reversal.lines).containsExactlyElementsOf(expected)
            assertThat(reversal.idempotencyKey).isEqualTo("treasury:${b.id}:reversed")
            assertThat(expected.map { key(it) }).doesNotHaveDuplicates()
        }
}
