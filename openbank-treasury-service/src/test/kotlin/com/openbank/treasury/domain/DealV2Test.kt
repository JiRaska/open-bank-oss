// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.libs.finance.schedule.Frequency
import com.openbank.treasury.domain.DealFixtures.NOW
import com.openbank.treasury.domain.DealFixtures.approver
import com.openbank.treasury.domain.DealFixtures.dealer
import com.openbank.treasury.domain.DealFixtures.withinLimit
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.Amortisation
import com.openbank.treasury.domain.model.AmortisationStep
import com.openbank.treasury.domain.model.AmortisationType
import com.openbank.treasury.domain.model.DayCountConvention
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealDocument
import com.openbank.treasury.domain.model.DealFee
import com.openbank.treasury.domain.model.DealHeader
import com.openbank.treasury.domain.model.DealKind
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.DocumentType
import com.openbank.treasury.domain.model.FourEyesViolationException
import com.openbank.treasury.domain.model.LegRate
import com.openbank.treasury.domain.model.LegSpec
import com.openbank.treasury.domain.model.LinkRelation
import com.openbank.treasury.domain.model.LinkedDeal
import com.openbank.treasury.domain.model.PayReceive
import com.openbank.treasury.domain.model.PostingRules
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.RateType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class DealV2Test {
    private val backOffice = Actor("bo-1", ActorType.HUMAN)
    private val start = LocalDate.parse("2026-11-02")
    private val irsLegs = listOf(
        LegSpec(
            PayReceive.PAY, "CZK", BigDecimal("10000000.00"), start, start.plusYears(2), DayCountConvention.ACT_360,
            LegRate.fixed(BigDecimal("4.00")), accrualFrequency = Frequency.ANNUAL, paymentFrequency = Frequency.ANNUAL,
        ),
        LegSpec(
            PayReceive.RECEIVE, "CZK", BigDecimal("10000000.00"), start, start.plusYears(2), DayCountConvention.ACT_360,
            LegRate(RateType.FLOATING, index = "PRIBOR_6M", resetFrequency = Frequency.SEMI_ANNUAL),
            accrualFrequency = Frequency.SEMI_ANNUAL,
            paymentFrequency = Frequency.SEMI_ANNUAL,
        ),
    )

    private fun irs(kind: DealKind = DealKind.ACTUAL, legs: List<LegSpec> = irsLegs) = Deal.draft(
        id = UUID.randomUUID(),
        product = ProductType.IRS,
        counterpartyId = "SIMBK-A",
        currency = "CZK",
        principal = legs.firstOrNull()?.notional ?: BigDecimal("10000000.00"),
        rate = BigDecimal("4.00"),
        tradeDate = LocalDate.parse("2026-10-29"),
        valueDate = start,
        maturityDate = start.plusYears(2),
        actor = dealer,
        at = NOW,
        kind = kind,
        legs = legs,
        header = DealHeader(bookId = "TRADING-BOOK", calendars = listOf("CERTIS")),
    )

    private fun booked(d: Deal) = d.submit(dealer, withinLimit(d), NOW, DealFixtures.withinProduct)
        .approve(approver, withinLimit(d), NOW, DealFixtures.withinProduct)

    @Test
    fun `a draft takes new terms - a human may edit it, its version grows and its timeline says so`() {
        val d = irs()
        val other = Actor("dora.dealer", ActorType.HUMAN)
        val edited = d.editDraft(irs().copy(rate = BigDecimal("4.10")), other, NOW)
        assertThat(edited.id).isEqualTo(d.id)
        assertThat(edited.createdBy).isEqualTo(d.createdBy)
        assertThat(edited.version).isEqualTo(d.version + 1)
        assertThat(edited.rate).isEqualByComparingTo("4.10")
        assertThat(edited.history.last().note).contains("draft edited")
        assertThat(edited.history.last().actor).isEqualTo(other)
    }

    @Test
    fun `an AI agent edits only its own draft, nobody edits a submitted deal, and the product is fixed`() {
        val agent = Actor("agent:treasury-dealing-assistant", ActorType.AI_AGENT)
        val d = irs()
        assertThatThrownBy { d.editDraft(irs(), agent, NOW) }.isInstanceOf(ActorNotPermittedException::class.java)
        val service = Actor("service-account-x", ActorType.SERVICE)
        assertThatThrownBy { d.editDraft(irs(), service, NOW) }.isInstanceOf(ActorNotPermittedException::class.java)
        val submitted = d.submit(dealer, withinLimit(d), NOW, DealFixtures.withinProduct)
        assertThatThrownBy { submitted.editDraft(irs(), dealer, NOW) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("only a DRAFT")
        assertThatThrownBy { d.editDraft(DealFixtures.placement(), dealer, NOW) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("product")
    }

    @Test
    fun `whoever edited a draft is a term author - they cannot approve it, a third person can`() {
        val editor = Actor("bea.dealer", ActorType.HUMAN)
        val edited = irs().editDraft(irs().copy(rate = BigDecimal("4.10")), editor, NOW)
        assertThat(edited.termEditors).containsExactly(editor.id)
        val submitted = edited.submit(dealer, withinLimit(edited), NOW, DealFixtures.withinProduct)
        assertThatThrownBy {
            submitted.approve(Actor(editor.id, ActorType.HUMAN), withinLimit(edited), NOW, DealFixtures.withinProduct)
        }
            .isInstanceOf(FourEyesViolationException::class.java).hasMessageContaining("draft editor")
        assertThat(
            submitted.approve(approver, withinLimit(edited), NOW, DealFixtures.withinProduct).state,
        ).isEqualTo(DealState.BOOKED)
    }

    @Test
    fun `a human editing an agent's draft drops the agent's rationale and inputs`() {
        val agent = Actor("agent:treasury-dealing-assistant", ActorType.AI_AGENT)
        val byAgent = irs().copy(createdBy = agent, rationale = "cheapest funding", inputs = "{\"quote\":1}")
        val edited = byAgent.editDraft(byAgent.copy(rate = BigDecimal("4.20")), dealer, NOW)
        assertThat(edited.rationale).isNull()
        assertThat(edited.inputs).isNull()
        assertThat(edited.history.last().note).contains("superseded")
        val ownEdit = byAgent.editDraft(byAgent.copy(rate = BigDecimal("4.20")), agent, NOW)
        assertThat(ownEdit.rationale).isEqualTo("cheapest funding")
    }

    @Test
    fun `the legacy products derive their legs from the flat fields`() {
        val d = DealFixtures.placement()
        assertThat(d.legs).singleElement().satisfies({
            assertThat(it.notional).isEqualByComparingTo(d.principal)
            assertThat(it.accrualStart).isEqualTo(d.valueDate)
            assertThat(it.accrualEnd).isEqualTo(d.maturityDate)
            assertThat(it.rate.rate).isEqualByComparingTo(d.rate)
            assertThat(it.payReceive).isEqualTo(PayReceive.RECEIVE)
            assertThat(it.adjustPaymentDate).isFalse()
        })
        assertThat(d.kind).isEqualTo(DealKind.ACTUAL)
        assertThat(d.version).isEqualTo(1)
        assertThat(d.scheduleVersion).isEqualTo(0)
    }

    @Test
    fun `a leg-described product needs its legs`() {
        assertThatThrownBy { irs(legs = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("legs")
    }

    @Test
    fun `a HYPOTHETICAL deal can never be submitted`() {
        val h = irs(DealKind.HYPOTHETICAL)
        assertThatThrownBy { h.submit(dealer, withinLimit(h), NOW, DealFixtures.withinProduct) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("HYPOTHETICAL")
    }

    @Test
    fun `a PLANNED deal books but never settles, posts or consumes a limit`() {
        val planned = DealFixtures.placement().copy(kind = DealKind.PLANNED)
        val b = booked(planned).confirm(approver, NOW)
        assertThat(b.consumesLimit).isFalse()
        assertThatThrownBy { b.settle(approver, LocalDate.parse("2026-12-31"), NOW) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("PLANNED")
    }

    @Test
    fun `a leg-described product settles through the posting-rule matrix, never the legacy posting table`() {
        val b = booked(irs()).confirm(approver, NOW)
        assertThat(b.state).isEqualTo(DealState.CONFIRMED)
        assertThat(b.settle(approver, LocalDate.parse("2027-12-31"), NOW).state).isEqualTo(DealState.SETTLED)
        assertThatThrownBy { PostingRules.settlement(b) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(PostingRules.accrual(b, start.plusDays(1))).isNull()
    }

    @Test
    fun `sending the confirmation is a person's act, never the creator's, and changes no state`() {
        val b = booked(DealFixtures.placement())
        val sent = b.recordConfirmationSent(backOffice, NOW, "doc-1", "SWIFT")
        assertThat(sent.state).isEqualTo(b.state)
        assertThat(sent.header.confirmationsSent).singleElement().satisfies({
            assertThat(it.documentId).isEqualTo("doc-1")
        })
        assertThat(sent.history.last().note).contains("confirmation sent")
        assertThatThrownBy {
            b.recordConfirmationSent(dealer, NOW, null, null)
        }.isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy { b.recordConfirmationSent(Actor("agent:x", ActorType.AI_AGENT), NOW, null, null) }
            .isInstanceOf(ActorNotPermittedException::class.java)
        assertThatThrownBy { DealFixtures.placement().recordConfirmationSent(backOffice, NOW, null, null) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `the header validates what it carries`() {
        assertThatThrownBy {
            DealHeader(jurisdiction = "CZE").validate()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            DealHeader(note = "x".repeat(2001)).validate()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            DealHeader(businessDayConvention = "NEAREST").validate()
        }.isInstanceOf(IllegalArgumentException::class.java)
        DealHeader(jurisdiction = "CZ", note = "ok", businessDayConvention = "MODIFIED_FOLLOWING").validate()
    }

    @Test
    fun `a schedule version only grows`() {
        val d = DealFixtures.placement().withSchedule(1, "h1")
        assertThat(d.scheduleVersion).isEqualTo(1)
        assertThatThrownBy { d.withSchedule(1, "h2") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `at most eight legs`() {
        assertThatThrownBy { irs(legs = List(9) { irsLegs[0] }) }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("8 legs")
        irs(legs = List(8) { irsLegs[0] })
    }

    @Test
    fun `a leg runs at most sixty years`() {
        assertThatThrownBy { irsLegs[0].copy(accrualEnd = start.plusYears(60).plusDays(1)).validate() }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("60 years")
        irsLegs[0].copy(accrualEnd = start.plusYears(60)).validate()
    }

    @Test
    fun `daily is the finest frequency there is`() {
        // The named codes have no finer value: an unknown frequency never reaches the domain.
        assertThat(Frequency.NAMED.keys).doesNotContain("HOURLY")
        assertThat(Frequency.named("HOURLY")).isNull()
    }

    @Test
    fun `a custom amortisation has at most two thousand rows`() {
        val rows =
            List(2001) {
                AmortisationStep(start.plusDays(it.toLong()), BigDecimal("0.01"))
            }
        val leg = irsLegs[0].copy(
            notional = BigDecimal("20.01"),
            amortisation = Amortisation(
                AmortisationType.CUSTOM,
                rows,
            ),
        )
        assertThatThrownBy {
            leg.validate()
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("2000 rows")
    }

    @Test
    fun `header arrays are bounded`() {
        val doc = DealDocument(
            "d",
            DocumentType.OTHER,
        )
        assertThatThrownBy { DealHeader(documents = List(101) { doc }).validate() }.hasMessageContaining("documents")
        val fee = DealFee("f", BigDecimal.ONE, "CZK", start)
        assertThatThrownBy { DealHeader(fees = List(101) { fee }).validate() }.hasMessageContaining("fees")
        assertThatThrownBy {
            DealHeader(
                linkedDeals = List(101) {
                    LinkedDeal(
                        UUID.randomUUID(),
                        LinkRelation.HEDGE,
                    )
                },
            ).validate()
        }.hasMessageContaining("linkedDeals")
        DealHeader(documents = List(100) { doc }).validate()
    }

    @Test
    fun `agent inputs are bounded`() {
        assertThatThrownBy { DealFixtures.placement().copy(inputs = "x".repeat(Deal.MAX_INPUTS_CHARS + 1)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("inputs")
    }

    @Test
    fun `an IRS whose flat principal disagrees with its legs is refused - limits measure the legs`() {
        val bigLegs = irsLegs.map { it.copy(notional = BigDecimal("10000000000.00")) }
        assertThatThrownBy {
            Deal.draft(
                id = UUID.randomUUID(), product = ProductType.IRS, counterpartyId = "SIMBK-A", currency = "CZK",
                principal = BigDecimal("1.00"), rate = BigDecimal("4.00"), tradeDate = LocalDate.parse("2026-10-29"),
                valueDate = start, maturityDate = start.plusYears(2), actor = dealer, at = NOW, legs = bigLegs,
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("principal")
        assertThatThrownBy { irs().copy(maturityDate = start.plusYears(1)) }.hasMessageContaining("maturityDate")
        assertThatThrownBy { irs().copy(valueDate = start.plusDays(1)) }.hasMessageContaining("valueDate")
        val measured = irs(legs = bigLegs)
        assertThat(measured.principal).isEqualByComparingTo("10000000000.00")
        assertThat(measured.days).isEqualTo(java.time.temporal.ChronoUnit.DAYS.between(start, start.plusYears(2)))
    }

    @Test
    fun `a 50-year daily leg is refused before any schedule is generated`() {
        val daily = irsLegs[0].copy(
            accrualEnd = start.plusYears(50),
            accrualFrequency = Frequency.DAILY,
            paymentFrequency = Frequency.DAILY,
        )
        assertThatThrownBy {
            daily.validate()
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("2000 periods")
    }

    @Test
    fun `a deal generates at most four thousand periods across its legs`() {
        // 5 legs of about 1 500 weekly periods each: every leg passes, the deal does not.
        val weekly = irsLegs[0].copy(
            accrualEnd = start.plusYears(28),
            accrualFrequency = Frequency.WEEKLY,
            paymentFrequency = Frequency.WEEKLY,
        )
        weekly.validate()
        assertThatThrownBy { irs(legs = List(5) { weekly }).copy() }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("4000 periods")
    }

    @Test
    fun `free code fields are codes`() {
        assertThatThrownBy {
            DealHeader(
                regulatory = com.openbank.treasury.domain.model.RegulatoryTerms(lcrCategory = "=HYPERLINK(1)"),
            ).validate()
        }.hasMessageContaining("not a code")
        assertThatThrownBy {
            DealHeader(
                regulatory = com.openbank.treasury.domain.model.RegulatoryTerms(centralBankCodes = listOf("a b")),
            ).validate()
        }.hasMessageContaining("not a code")
        assertThatThrownBy {
            DealHeader(
                regulatory = com.openbank.treasury.domain.model.RegulatoryTerms(nsfrCategory = "X".repeat(33)),
            ).validate()
        }
            .hasMessageContaining("not a code")
        DealHeader(
            regulatory = com.openbank.treasury.domain.model.RegulatoryTerms(
                lcrCategory = "L1",
                centralBankCodes = listOf("CB-01"),
            ),
        ).validate()
    }
}
