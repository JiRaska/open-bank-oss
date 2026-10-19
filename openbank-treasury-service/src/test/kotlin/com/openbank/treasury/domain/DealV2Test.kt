// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

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
import com.openbank.treasury.domain.model.Frequency
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
        principal = BigDecimal("10000000.00"),
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
    fun `a leg-described product books but cannot settle until its posting rules exist`() {
        val b = booked(irs()).confirm(approver, NOW)
        assertThat(b.state).isEqualTo(DealState.CONFIRMED)
        assertThatThrownBy { b.settle(approver, LocalDate.parse("2027-12-31"), NOW) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("posting rules")
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
        // The request enum has no finer value: an unknown frequency never reaches the domain.
        assertThat(Frequency.entries.map { it.name }).doesNotContain("HOURLY")
        assertThatThrownBy { Frequency.valueOf("HOURLY") }.isInstanceOf(IllegalArgumentException::class.java)
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
}
