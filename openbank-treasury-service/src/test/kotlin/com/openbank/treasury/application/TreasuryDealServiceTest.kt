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
import com.openbank.treasury.application.port.out.FxMidRatePort
import com.openbank.treasury.application.port.out.FxRateTolerance
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.UnknownCounterpartyException
import com.openbank.treasury.application.usecase.TreasuryDealService
import com.openbank.treasury.domain.DealFixtures
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.CounterpartyKind
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.FxSide
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.LimitBreachedException
import com.openbank.treasury.domain.model.PostingEvent
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
    private var fxMid: FxMidRatePort = FxMidRatePort.NONE
    private var tolerance: FxRateTolerance = FxRateTolerance.DISABLED
    private var confirmationRequired = true
    private val service get() =
        TreasuryDealService(deals, cps, ledger, mapper, clock, fxMid, tolerance, confirmationRequired)

    /** Bank buys (or sells) EUR against CZK; value date left to default (T+2). */
    private fun fx(
        eur: String = "10000.00",
        rate: String = "25.000000",
        side: FxSide = FxSide.BUY,
        valueDate: LocalDate? = null,
    ) = DraftDealCommand(
        ProductType.FX_SPOT,
        "SIMBK-A",
        "EUR",
        BigDecimal(eur),
        BigDecimal(rate),
        null,
        valueDate,
        null,
        null,
        fxSide = side,
    )

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
    fun `the simulated market confirms, settles on value date and matures on maturity, posting each once`(): Unit =
        runBlocking {
            val b = book()
            assertThat(service.runSimulatedMarket().moved).describedAs("confirm + settle").isEqualTo(2)
            assertThat(deals.findById(b.id)!!.state).isEqualTo(DealState.SETTLED)
            assertThat(deals.findById(b.id)!!.history.map { it.to }).containsSubsequence(
                DealState.BOOKED,
                DealState.CONFIRMED,
                DealState.SETTLED,
            )
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
        assertThat(run.moved).describedAs("both confirmed, only b settled").isEqualTo(3)
        assertThat(run.failures).hasSize(1)
        assertThat(deals.findById(a.id)!!.state).isEqualTo(DealState.CONFIRMED)
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

    // --- #10896: FX spot ---------------------------------------------------------------------------

    @Test
    fun `an FX spot defaults to T+2 and is checked against the CZK limit by its CZK equivalent`(): Unit = runBlocking {
        book(cmd(principal = "700000.00")) // a CZK placement: 700k of the 1M CZK limit
        val d = service.draft(fx(eur = "10000.00", rate = "25.000000"), DealFixtures.dealer)
        assertThat(d.valueDate).isEqualTo(monday.plusDays(2))
        assertThat(d.maturityDate).isEqualTo(d.valueDate)
        val pending = service.submit(d.id, DealFixtures.dealer)
        assertThat(pending.limitCheck!!.currency).isEqualTo("CZK")
        assertThat(pending.limitCheck!!.dealAmount).isEqualByComparingTo("250000.00")
        assertThat(pending.limitCheck!!.exposureBefore).isEqualByComparingTo("700000.00")
        assertThat(pending.limitCheck!!.breached).isFalse()
        // The same EUR amount at a rate that pushes the CZK leg past the headroom breaches.
        val big = service.draft(fx(eur = "20000.00", rate = "25.000000"), DealFixtures.dealer)
        assertThat(service.submit(big.id, DealFixtures.dealer).limitCheck!!.breached)
            .describedAs("500k CZK on top of 700k + 250k exceeds 1M").isTrue()
        assertThatThrownBy { runBlocking { service.approve(big.id, DealFixtures.approver) } }
            .isInstanceOf(LimitBreachedException::class.java)
    }

    @Test
    fun `an FX spot drafted on a Friday values on the next Tuesday`(): Unit = runBlocking {
        clock = Clock.fixed(DealFixtures.FRIDAY.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        assertThat(service.draft(fx(), DealFixtures.dealer).valueDate).isEqualTo(DealFixtures.FRIDAY.plusDays(4))
    }

    @Test
    fun `an FX spot settles both legs once on its value date and never matures`(): Unit = runBlocking {
        val b = book(fx(eur = "1000.00", rate = "24.915000"))
        assertThat(deals.events.single().payload).contains("\"fxSide\":\"BUY\"").contains("\"counterAmount\":24915.00")
        assertThat(service.runSimulatedMarket().moved).describedAs("confirmed, not yet settled").isEqualTo(1)
        clock = Clock.offset(clock, java.time.Duration.ofDays(2))
        assertThat(service.runSimulatedMarket().moved).isEqualTo(1)
        val settlement = ledger.posted.single()
        assertThat(settlement.idempotencyKey).isEqualTo("treasury:${b.id}:settled")
        assertThat(settlement.lines.map { "${it.side} ${it.glCode} ${it.amount.toPlainString()} ${it.currency}" })
            .containsExactly(
                "DEBIT 1002 1000.00 EUR",
                "CREDIT 1991 1000.00 EUR",
                "DEBIT 1990 24915.00 CZK",
                "CREDIT 1001 24915.00 CZK",
            )
        clock = Clock.offset(clock, java.time.Duration.ofDays(30))
        assertThat(
            service.runSimulatedMarket().moved,
        ).describedAs("a settled spot is not due for maturity").isEqualTo(0)
        assertThat(service.accrueInterest(LocalDate.now(clock)).journals).isEqualTo(0)
        assertThatThrownBy { runBlocking { service.mature(b.id, DealFixtures.approver) } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(ledger.posted).hasSize(1)
        val czk = service.counterparties().first { it.counterparty.id == "SIMBK-A" && it.currency == "CZK" }
        assertThat(czk.exposure).describedAs("settlement releases the limit").isEqualByComparingTo("0")
    }

    @Test
    fun `reversing a settled FX spot posts the flipped four legs`(): Unit = runBlocking {
        val b = book(fx(side = FxSide.SELL))
        clock = Clock.offset(clock, java.time.Duration.ofDays(2))
        service.runSimulatedMarket()
        service.reverse(b.id, "wrong side", DealFixtures.approver)
        val (settled, reversed) = ledger.posted
        assertThat(settled.lines.first().let { it.side to it.glCode }).isEqualTo(Side.CREDIT to "1002")
        assertThat(reversed.lines.map { it.glCode to it.side }).isEqualTo(
            settled.lines.map { it.glCode to (if (it.side == Side.DEBIT) Side.CREDIT else Side.DEBIT) },
        )
        assertThat(deals.events.last().eventType).isEqualTo("treasury.deal.reversed.v1")
        assertThat(deals.events.last().payload).contains("\"fxSide\":\"SELL\"")
    }

    @Test
    fun `the rate check is off by default, and when on flags - never blocks - a rate outside tolerance`(): Unit =
        runBlocking {
            assertThat(service.draft(fx(), DealFixtures.dealer).fx!!.rateFlag).isNull()
            tolerance = FxRateTolerance(true, BigDecimal("1.0"))
            fxMid = FxMidRatePort { ccy, _ -> if (ccy == "EUR") BigDecimal("25.00") else null }
            val within = service.draft(fx(rate = "25.200000"), DealFixtures.dealer)
            assertThat(within.fx!!.rateFlag).isNull()
            assertThat(within.fx!!.midRate).isEqualByComparingTo("25.00")
            val off = service.draft(fx(rate = "26.000000"), DealFixtures.dealer)
            assertThat(off.fx!!.rateFlag).contains("deviates 4.0000 %")
            assertThat(off.history.last().note).startsWith("rate flagged:")
            assertThat(service.submit(off.id, DealFixtures.dealer).state).isEqualTo(DealState.PENDING_APPROVAL)
            fxMid = FxMidRatePort.NONE
            assertThat(service.draft(fx(), DealFixtures.dealer).fx!!.rateFlag).contains("mid unavailable")
        }

    // --- ADR-0315 D2: CONFIRMED ------------------------------------------------------------------

    @Test
    fun `a back-office confirm emits the confirmed event via the outbox and posts nothing`(): Unit = runBlocking {
        val b = book()
        val c = service.confirm(b.id, "CPTY-42", DealFixtures.approver, "k-confirm")
        assertThat(c.state).isEqualTo(DealState.CONFIRMED)
        assertThat(ledger.posted).isEmpty()
        val event = deals.events.last()
        assertThat(event.eventType).isEqualTo("treasury.deal.confirmed.v1")
        assertThat(event.payload).contains("\"confirmedBy\":\"adam.approver\"").contains("\"simulated\":false")
        // A replay of the key answers with the deal as it stands, and emits nothing more.
        assertThat(service.confirm(b.id, "CPTY-42", DealFixtures.approver, "k-confirm").state)
            .isEqualTo(DealState.CONFIRMED)
        assertThat(deals.events.count { it.eventType == "treasury.deal.confirmed.v1" }).isEqualTo(1)
    }

    @Test
    fun `the simulated confirmation is labelled simulated on the event`(): Unit = runBlocking {
        book()
        service.runSimulatedMarket()
        assertThat(deals.events.single { it.eventType == "treasury.deal.confirmed.v1" }.payload)
            .contains("\"simulated\":true")
            .contains("\"confirmedBy\":\"system:simulated-market\"")
    }

    @Test
    fun `with confirmation required a BOOKED deal is refused settlement until confirmed`(): Unit = runBlocking {
        val b = book()
        assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("confirmation is required")
        assertThat(ledger.posted).describedAs("a refused settlement posts nothing").isEmpty()
        service.confirm(b.id, null, DealFixtures.approver)
        assertThat(service.settle(b.id, DealFixtures.approver).state).isEqualTo(DealState.SETTLED)
    }

    @Test
    fun `with confirmation not required a legacy BOOKED deal settles directly, manually and in the market`(): Unit =
        runBlocking {
            confirmationRequired = false
            val manual = book()
            assertThat(service.settle(manual.id, DealFixtures.approver).state).isEqualTo(DealState.SETTLED)
            assertThat(deals.findById(manual.id)!!.history.map { it.to }).doesNotContain(DealState.CONFIRMED)
            val market = book(cmd(principal = "1000.00"))
            service.runSimulatedMarket()
            assertThat(deals.findById(market.id)!!.state).isEqualTo(DealState.SETTLED)
        }

    @Test
    fun `an agent cannot confirm through the use case either`(): Unit = runBlocking {
        val b = book()
        assertThatThrownBy { runBlocking { service.confirm(b.id, null, DealFixtures.agent) } }
            .isInstanceOf(com.openbank.treasury.domain.model.ActorNotPermittedException::class.java)
        assertThat(deals.findById(b.id)!!.state).isEqualTo(DealState.BOOKED)
        assertThat(deals.events.map { it.eventType }).doesNotContain("treasury.deal.confirmed.v1")
    }

    @Test
    fun `a money-market draft without a value date is refused`(): Unit = runBlocking {
        val noDate = cmd().copy(valueDate = null)
        assertThatThrownBy { runBlocking { service.draft(noDate, DealFixtures.dealer) } }
            .isInstanceOf(IllegalArgumentException::class.java)
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
        override suspend fun dueForSettlement(today: LocalDate, states: Set<DealState>) =
            rows.values.filter { it.state in states && !it.valueDate.isAfter(today) }
        override suspend fun dueForMaturity(today: LocalDate) = rows.values.filter {
            it.state == DealState.SETTLED && !it.maturityDate.isAfter(today) && it.product != ProductType.FX_SPOT
        }
        override suspend fun exposure(counterpartyId: String, currency: String, excludeDealId: UUID?) =
            rows.values.filter {
                it.counterpartyId == counterpartyId &&
                    it.limitCurrency == currency &&
                    it.consumesLimit &&
                    it.id != excludeDealId
            }.sumOf { it.limitAmount }
        override suspend fun pendingLimitOverrides() =
            rows.values.filter { it.state == DealState.PENDING_APPROVAL && it.limitOverride != null }
        override suspend fun journals(dealId: UUID) = journals.filter { it.dealId == dealId }
        override suspend fun recordJournal(journal: LedgerJournalRef) {
            if (journals.none { it.idempotencyKey == journal.idempotencyKey }) journals += journal
        }
    }
}
