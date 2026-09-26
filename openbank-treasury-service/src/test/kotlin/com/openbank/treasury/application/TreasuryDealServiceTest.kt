// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.treasury.application.port.`in`.DraftDealCommand
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.UnknownCounterpartyException
import com.openbank.treasury.application.usecase.TreasuryDealService
import com.openbank.treasury.domain.DealFixtures
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.CounterpartyKind
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.LimitBreachedException
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.ProductType
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class TreasuryDealServiceTest {

    private val monday = DealFixtures.MONDAY
    private var clock: Clock = Clock.fixed(monday.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private val deals = InMemoryDeals()
    private val ledger = RecordingLedger()
    private val cps = object : CounterpartyRepository {
        val all = listOf(
            DealFixtures.bankA,
            Counterparty("CNB", "ČNB", CounterpartyKind.CENTRAL_BANK, mapOf("CZK" to BigDecimal("1E12")), false),
        )
        override suspend fun findById(id: String) = all.find { it.id == id }
        override suspend fun list() = all
    }
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val service get() = TreasuryDealService(deals, cps, ledger, mapper, clock)

    private fun cmd(
        principal: String = "100000.00",
        product: ProductType = ProductType.MM_PLACEMENT,
        cp: String = "SIMBK-A",
        maturity: LocalDate? = monday.plusDays(7),
    ) = DraftDealCommand(product, cp, "CZK", BigDecimal(principal), BigDecimal("4.00"), null, monday, maturity, null)

    private suspend fun book(c: DraftDealCommand = cmd()): Deal {
        val d = service.draft(c, DealFixtures.dealer)
        service.submit(d.id, DealFixtures.dealer)
        return service.approve(d.id, DealFixtures.approver)
    }

    @Test
    fun `an unknown counterparty is a bad request`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.draft(cmd(cp = "NOPE"), DealFixtures.dealer) } }
            .isInstanceOf(UnknownCounterpartyException::class.java)
    }

    @Test
    fun `booking emits the booked event and posts nothing`(): Unit = runBlocking {
        val b = book()
        assertThat(b.state).isEqualTo(DealState.BOOKED)
        assertThat(ledger.posted).isEmpty()
        assertThat(deals.events.map { it.eventType }).containsExactly("treasury.deal.booked.v1")
    }

    @Test
    fun `exposure of pending and booked placements counts against the next approval`(): Unit = runBlocking {
        book(cmd(principal = "700000.00"))
        val second = service.draft(cmd(principal = "400000.00"), DealFixtures.dealer)
        val pending = service.submit(second.id, DealFixtures.dealer)
        assertThat(pending.limitCheck!!.breached).isTrue()
        assertThatThrownBy { runBlocking { service.approve(second.id, DealFixtures.approver) } }
            .isInstanceOf(LimitBreachedException::class.java)
    }

    @Test
    fun `the simulated market settles on value date and matures on maturity, posting each once`(): Unit = runBlocking {
        val b = book()
        assertThat(service.runSimulatedMarket().moved).isEqualTo(1)
        assertThat(deals.findById(b.id)!!.state).isEqualTo(DealState.SETTLED)
        assertThat(service.runSimulatedMarket().moved).isEqualTo(0)
        clock = Clock.offset(clock, java.time.Duration.ofDays(7))
        assertThat(service.runSimulatedMarket().moved).isEqualTo(1)
        assertThat(deals.findById(b.id)!!.state).isEqualTo(DealState.MATURED)
        // Maturity first catches the daily accrual up (ADR-0315 D5): seven days, then the maturity.
        assertThat(ledger.posted.map { it.idempotencyKey }).containsExactly(
            "treasury:${b.id}:settled",
            *(1L..7L).map { "treasury:${b.id}:accrued:${monday.plusDays(it)}" }.toTypedArray(),
            "treasury:${b.id}:matured",
        )
        assertThat(deals.findById(b.id)!!.history.last().actor.id).isEqualTo("system:simulated-market")
    }

    @Test
    fun `a failing deal does not stop the simulated market pass, and is reported`(): Unit = runBlocking {
        val a = book()
        val b = book()
        ledger.failFor = a.id
        val run = service.runSimulatedMarket()
        assertThat(run.moved).isEqualTo(1)
        assertThat(run.failures).hasSize(1)
        assertThat(deals.findById(a.id)!!.state).isEqualTo(DealState.BOOKED)
        assertThat(deals.findById(b.id)!!.state).isEqualTo(DealState.SETTLED)
    }

    @Test
    fun `positions count settled deals outstanding on the date, per currency`(): Unit = runBlocking {
        book(cmd(principal = "100000.00"))
        book(cmd(principal = "30000.00", product = ProductType.MM_BORROWING))
        book(cmd(principal = "5000000.00", product = ProductType.CNB_DEPOSIT_FACILITY, cp = "CNB", maturity = null))
        assertThat(service.positions(monday).first { it.currency == "CZK" }.placed).isEqualByComparingTo("0")
        service.runSimulatedMarket()
        val czk = service.positions(monday).first { it.currency == "CZK" }
        assertThat(czk.placed).isEqualByComparingTo("100000.00")
        assertThat(czk.borrowed).isEqualByComparingTo("30000.00")
        assertThat(czk.atCnb).isEqualByComparingTo("5000000.00")
        assertThat(czk.net).isEqualByComparingTo("5070000.00")
        assertThat(service.positions(monday.plusDays(1)).first { it.currency == "CZK" }.atCnb)
            .describedAs("the overnight facility has matured by Tuesday").isEqualByComparingTo("0")
    }

    @Test
    fun `counterparty view shows limit, exposure and headroom per currency line`(): Unit = runBlocking {
        book(cmd(principal = "250000.00"))
        val a = service.counterparties().first { it.counterparty.id == "SIMBK-A" && it.currency == "CZK" }
        assertThat(a.exposure).isEqualByComparingTo("250000.00")
        assertThat(a.headroom).isEqualByComparingTo("750000.00")
        assertThat(a.utilisationPercent).isEqualByComparingTo("25.00")
        assertThat(a.breached).isFalse()
        assertThat(a.activeOverrides).isEqualTo(0)
    }

    /**
     * #10896: the limit-utilisation view (`counterparties()`) and the booking-time [LimitCheck] both
     * read [DealRepository.exposure] — proving they cannot disagree means proving the view's
     * `exposure` for a counterparty/currency equals what a fresh [com.openbank.treasury.domain.model.LimitCheck]
     * computes for the NEXT deal against the same book. Both numbers come from the identical
     * on-book deals (`Deal.LIMIT_CONSUMING_STATES`), so they must match exactly.
     */
    @Test
    fun `the limit-utilisation view and the booking-time limit check agree on exposure`(): Unit = runBlocking {
        book(cmd(principal = "250000.00"))
        val pendingOnly = service.draft(cmd(principal = "50000.00"), DealFixtures.dealer)
        val pending = service.submit(pendingOnly.id, DealFixtures.dealer)

        val view = service.counterparties().first { it.counterparty.id == "SIMBK-A" && it.currency == "CZK" }
        // the view's exposure already counts BOOKED + PENDING_APPROVAL, exactly like the check.
        assertThat(view.exposure).isEqualByComparingTo("300000.00")
        assertThat(pending.limitCheck!!.exposureBefore).isEqualByComparingTo("250000.00")
        assertThat(pending.limitCheck!!.exposureAfter).isEqualByComparingTo(view.exposure)
    }

    @Test
    fun `an active override counts while PENDING_APPROVAL, and not after booking`(): Unit = runBlocking {
        book(cmd(principal = "700000.00"))
        val second = service.draft(cmd(principal = "400000.00"), DealFixtures.dealer)
        val pending = service.submit(second.id, DealFixtures.dealer)
        assertThat(pending.limitCheck!!.breached).isTrue()

        service.overrideLimit(second.id, "desk head approved, temporary excess", DealFixtures.seniorApprover)
        val afterOverride = service.counterparties().first {
            it.counterparty.id == "SIMBK-A" && it.currency == "CZK"
        }
        assertThat(afterOverride.activeOverrides).isEqualTo(1)
        assertThat(afterOverride.breached).isTrue()

        service.approve(second.id, DealFixtures.approver)
        val afterBooking = service.counterparties().first {
            it.counterparty.id == "SIMBK-A" && it.currency == "CZK"
        }
        assertThat(afterBooking.activeOverrides)
            .describedAs("booking moves the deal off PENDING_APPROVAL; the override is no longer 'active'")
            .isEqualTo(0)
    }

    @Test
    fun `a replayed key returns the deal as it stands, reuse on another command is refused`(): Unit = runBlocking {
        val d = service.draft(cmd(), DealFixtures.dealer, "k-draft")
        assertThat(service.draft(cmd(principal = "999.00"), DealFixtures.dealer, "k-draft").id).isEqualTo(d.id)
        service.submit(d.id, DealFixtures.dealer, "k-submit")
        assertThat(
            service.submit(d.id, DealFixtures.dealer, "k-submit").state,
        ).isEqualTo(DealState.PENDING_APPROVAL)
        assertThatThrownBy { runBlocking { service.cancel(d.id, DealFixtures.dealer, "k-submit") } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(deals.rows).hasSize(1)
    }

    // --- ADR-0315 D5: daily accrual --------------------------------------------------------------

    @Test
    fun `accrual posts each missing day once, and a second pass the same day posts nothing`(): Unit = runBlocking {
        val b = book()
        service.runSimulatedMarket() // settles on the value date
        clock = Clock.offset(clock, java.time.Duration.ofDays(3))
        assertThat(service.accrueInterest(LocalDate.now(clock)).journals).isEqualTo(3)
        assertThat(service.accrueInterest(LocalDate.now(clock)).journals).isEqualTo(0)
        val accruals = ledger.posted.filter { it.event == PostingEvent.ACCRUED }
        assertThat(accruals.map { it.idempotencyKey }).containsExactly(
            "treasury:${b.id}:accrued:${monday.plusDays(1)}",
            "treasury:${b.id}:accrued:${monday.plusDays(2)}",
            "treasury:${b.id}:accrued:${monday.plusDays(3)}",
        )
    }

    @Test
    fun `after a full accrual run the maturity books nothing more to income`(): Unit = runBlocking {
        val b = book()
        service.runSimulatedMarket()
        clock = Clock.offset(clock, java.time.Duration.ofDays(7))
        service.accrueInterest(LocalDate.now(clock))
        service.runSimulatedMarket() // matures
        val deal = deals.findById(b.id)!!
        val accrued = ledger.posted.filter { it.event == PostingEvent.ACCRUED }.sumOf { it.lines.first().amount }
        assertThat(accrued).isEqualByComparingTo(deal.interest)
        val maturity = ledger.posted.single { it.event == PostingEvent.MATURED }
        assertThat(maturity.lines.map { it.glCode }).doesNotContain("4200").contains("1520")
    }

    @Test
    fun `reversing a settled deal unwinds what had accrued`(): Unit = runBlocking {
        val b = book()
        service.runSimulatedMarket()
        clock = Clock.offset(clock, java.time.Duration.ofDays(2))
        service.accrueInterest(LocalDate.now(clock))
        service.reverse(b.id, "test", DealFixtures.approver)
        val accrued = ledger.posted.filter { it.event == PostingEvent.ACCRUED }.sumOf { it.lines.first().amount }
        val reversal = ledger.posted.single { it.event == PostingEvent.REVERSED }
        val unwound = reversal.lines.single { it.glCode == "4200" }
        assertThat(unwound.amount).isEqualByComparingTo(accrued)
    }

    @Test
    fun `a failing deal does not stop the accrual pass, and is reported`(): Unit = runBlocking {
        val a = book()
        val b = book(cmd(principal = "50000.00"))
        service.runSimulatedMarket()
        clock = Clock.offset(clock, java.time.Duration.ofDays(1))
        ledger.failFor = a.id
        val run = service.accrueInterest(LocalDate.now(clock))
        assertThat(run.failures).hasSize(1)
        assertThat(run.journals).isEqualTo(1)
        assertThat(ledger.posted.filter { it.event == PostingEvent.ACCRUED }.map { it.dealId }).containsExactly(b.id)
    }

    private class RecordingLedger : LedgerPostingPort {
        val posted = mutableListOf<JournalSpec>()
        var failFor: UUID? = null
        override suspend fun post(spec: JournalSpec, entryDate: LocalDate, description: String): UUID {
            if (spec.dealId == failFor) error("ledger unavailable")
            posted += spec
            return UUID.nameUUIDFromBytes(spec.idempotencyKey.toByteArray())
        }
    }

    private class InMemoryDeals : DealRepository {
        val rows = linkedMapOf<UUID, Deal>()
        val journals = mutableListOf<LedgerJournalRef>()
        val events = mutableListOf<DealEvent>()

        val commands = mutableMapOf<String, CommandKey>()

        override suspend fun save(
            deal: Deal,
            journal: LedgerJournalRef?,
            event: DealEvent?,
            command: CommandKey?,
        ): Deal {
            rows[deal.id] = deal
            journal?.let { journals += it }
            event?.let { events += it }
            command?.let { commands[it.key] = it }
            return deal
        }

        override suspend fun findCommand(key: String) = commands[key]

        override suspend fun findById(dealId: UUID) = rows[dealId]
        override suspend fun list(state: DealState?) = rows.values.filter { state == null || it.state == state }
        override suspend fun dueForSettlement(today: LocalDate) =
            rows.values.filter { it.state == DealState.BOOKED && !it.valueDate.isAfter(today) }
        override suspend fun dueForMaturity(today: LocalDate) =
            rows.values.filter { it.state == DealState.SETTLED && !it.maturityDate.isAfter(today) }
        override suspend fun exposure(counterpartyId: String, currency: String, excludeDealId: UUID?) =
            rows.values.filter {
                it.counterpartyId == counterpartyId &&
                    it.currency == currency &&
                    it.consumesLimit &&
                    it.id != excludeDealId
            }.sumOf { it.principal }
        override suspend fun activeLimitOverrideCount(counterpartyId: String, currency: String) = rows.values.count {
            it.counterpartyId == counterpartyId &&
                it.currency == currency &&
                it.state == DealState.PENDING_APPROVAL &&
                it.limitOverride != null
        }
        override suspend fun journals(dealId: UUID) = journals.filter { it.dealId == dealId }
        override suspend fun recordJournal(journal: LedgerJournalRef) {
            if (journals.none { it.idempotencyKey == journal.idempotencyKey }) journals += journal
        }
    }
}
