// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.treasury.domain.DealFixtures.FRIDAY
import com.openbank.treasury.domain.DealFixtures.MONDAY
import com.openbank.treasury.domain.DealFixtures.NOW
import com.openbank.treasury.domain.DealFixtures.agent
import com.openbank.treasury.domain.DealFixtures.approver
import com.openbank.treasury.domain.DealFixtures.bankA
import com.openbank.treasury.domain.DealFixtures.dealer
import com.openbank.treasury.domain.DealFixtures.placement
import com.openbank.treasury.domain.DealFixtures.serviceAccount
import com.openbank.treasury.domain.DealFixtures.withinLimit
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.DayCount
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.FourEyesViolationException
import com.openbank.treasury.domain.model.LimitBreachedException
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.ProductType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class DealTest {

    private fun booked(): Deal {
        val d = placement()
        return d.submit(dealer, withinLimit(d), NOW).approve(approver, withinLimit(d), NOW)
    }

    @Nested
    inner class Lifecycle {
        @Test
        fun `the happy path walks DRAFT to MATURED and records every step`() {
            val matured = booked()
                .settle(approver, MONDAY, NOW)
                .mature(Actor.SIMULATED_MARKET, LocalDate.parse("2026-10-21"), NOW)
            assertThat(matured.state).isEqualTo(DealState.MATURED)
            assertThat(matured.history.map { it.to }).containsExactly(
                DealState.DRAFT,
                DealState.PENDING_APPROVAL,
                DealState.BOOKED,
                DealState.SETTLED,
                DealState.MATURED,
            )
            assertThat(matured.approvedBy).isEqualTo(approver)
        }

        @Test
        fun `a rejection returns the deal to DRAFT with the reason on its timeline`() {
            val d = placement()
            val rejected = d.submit(dealer, withinLimit(d), NOW).reject(approver, "rate off-market", NOW)
            assertThat(rejected.state).isEqualTo(DealState.DRAFT)
            assertThat(rejected.history.last().note).contains("rate off-market")
            assertThat(rejected.submittedBy).isNull()
        }

        @Test
        fun `cancel is allowed before booking only`() {
            assertThat(placement().cancel(dealer, NOW).state).isEqualTo(DealState.CANCELLED)
            assertThatThrownBy { booked().cancel(dealer, NOW) }.isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `a deal cannot settle before its value date nor mature before maturity`() {
            val d = placement()
            val future = Deal.draft(
                d.id, d.product, d.counterpartyId, d.currency, d.principal, d.rate,
                MONDAY, FRIDAY, null, dealer, NOW,
            )
            val b = future.submit(dealer, withinLimit(future), NOW).approve(approver, withinLimit(future), NOW)
            assertThatThrownBy { b.settle(approver, MONDAY, NOW) }.isInstanceOf(IllegalStateException::class.java)
            val settled = booked().settle(approver, MONDAY, NOW)
            assertThatThrownBy { settled.mature(approver, MONDAY, NOW) }.isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `a booked or settled deal can be reversed, a matured one cannot`() {
            assertThat(booked().reverse(approver, "wrong counterparty", NOW).state).isEqualTo(DealState.REVERSED)
            assertThat(booked().settle(approver, MONDAY, NOW).reverse(approver, "fat finger", NOW).state)
                .isEqualTo(DealState.REVERSED)
            val matured = booked().settle(approver, MONDAY, NOW).mature(approver, LocalDate.parse("2026-10-21"), NOW)
            assertThatThrownBy {
                matured.reverse(approver, "too late", NOW)
            }.isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `approving a DRAFT that was never submitted is refused`() {
            val d = placement()
            assertThatThrownBy {
                d.approve(approver, withinLimit(d), NOW)
            }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Nested
    inner class FourEyes {
        @Test
        fun `the creator cannot approve their own deal`() {
            val d = placement(by = dealer)
            val pending = d.submit(dealer, withinLimit(d), NOW)
            assertThatThrownBy { pending.approve(dealer, withinLimit(d), NOW) }
                .isInstanceOf(FourEyesViolationException::class.java)
                .hasMessageContaining("creator")
        }

        @Test
        fun `the submitter cannot approve either`() {
            val d = placement(by = dealer)
            val colleague = Actor("carl.colleague", ActorType.HUMAN)
            val pending = d.submit(colleague, withinLimit(d), NOW)
            assertThatThrownBy { pending.approve(colleague, withinLimit(d), NOW) }
                .isInstanceOf(FourEyesViolationException::class.java)
                .hasMessageContaining("submitter")
        }

        @Test
        fun `the creator cannot reverse their own deal`() {
            assertThatThrownBy {
                booked().reverse(dealer, "oops", NOW)
            }.isInstanceOf(FourEyesViolationException::class.java)
        }
    }

    @Nested
    inner class NonHumanPrincipals {
        @Test
        fun `an AI agent may draft, with a rationale`() {
            val d = placement(by = agent)
            assertThat(d.state).isEqualTo(DealState.DRAFT)
            assertThat(d.rationale).isNotBlank()
        }

        @Test
        fun `an AI agent draft without a rationale is refused`() {
            assertThatThrownBy {
                Deal.draft(
                    placement().id, ProductType.MM_PLACEMENT, "SIMBK-A", "CZK", BigDecimal.TEN, BigDecimal.ONE,
                    MONDAY, MONDAY, null, agent, NOW, rationale = null,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `an AI agent can never approve, even a deal a human created and submitted`() {
            val d = placement(by = dealer)
            val pending = d.submit(dealer, withinLimit(d), NOW)
            assertThatThrownBy { pending.approve(agent, withinLimit(d), NOW) }
                .isInstanceOf(ActorNotPermittedException::class.java)
        }

        @Test
        fun `an AI agent can never submit, settle, mature or reverse`() {
            val d = placement(by = agent)
            assertThatThrownBy {
                d.submit(agent, withinLimit(d), NOW)
            }.isInstanceOf(ActorNotPermittedException::class.java)
            val b = booked()
            assertThatThrownBy { b.settle(agent, MONDAY, NOW) }.isInstanceOf(ActorNotPermittedException::class.java)
            assertThatThrownBy { b.reverse(agent, "x", NOW) }.isInstanceOf(ActorNotPermittedException::class.java)
            val s = b.settle(approver, MONDAY, NOW)
            assertThatThrownBy { s.mature(agent, LocalDate.parse("2026-10-21"), NOW) }
                .isInstanceOf(ActorNotPermittedException::class.java)
        }

        @Test
        fun `a service account can neither draft nor approve`() {
            assertThatThrownBy { placement(by = serviceAccount) }.isInstanceOf(ActorNotPermittedException::class.java)
            val d = placement()
            val pending = d.submit(dealer, withinLimit(d), NOW)
            assertThatThrownBy { pending.approve(serviceAccount, withinLimit(d), NOW) }
                .isInstanceOf(ActorNotPermittedException::class.java)
        }

        @Test
        fun `principal names are classified like the authz interceptor does`() {
            assertThat(Actor.fromPrincipalName("agent:x").type).isEqualTo(ActorType.AI_AGENT)
            assertThat(Actor.fromPrincipalName("service-account-openbank-treasury").type).isEqualTo(ActorType.SERVICE)
            assertThat(Actor.fromPrincipalName("jane").type).isEqualTo(ActorType.HUMAN)
        }
    }

    @Nested
    inner class Limits {
        @Test
        fun `a breach at approval blocks booking`() {
            val d = placement(principal = "600000.00")
            val pending = d.submit(dealer, withinLimit(d), NOW)
            val breach = LimitCheck.of(bankA, d, BigDecimal("500000.00"))
            assertThat(breach.breached).isTrue()
            assertThatThrownBy {
                pending.approve(approver, breach, NOW)
            }.isInstanceOf(LimitBreachedException::class.java)
        }

        @Test
        fun `exactly at the limit is not a breach`() {
            val d = placement(principal = "1000000.00")
            val check = LimitCheck.of(bankA, d, BigDecimal.ZERO)
            assertThat(check.breached).isFalse()
            assertThat(check.headroomAfter).isEqualByComparingTo("0")
        }

        @Test
        fun `a borrowing consumes no limit`() {
            val d = placement(principal = "99000000.00", product = ProductType.MM_BORROWING)
            val check = LimitCheck.of(bankA, d, BigDecimal("1000000.00"))
            assertThat(check.breached).isFalse()
            assertThat(d.consumesLimit).isFalse()
        }

        @Test
        fun `a currency with no limit line breaches on any placement`() {
            val cp = bankA.copy(limits = mapOf("CZK" to BigDecimal.ONE))
            val d = placement(currency = "EUR", principal = "1.00")
            assertThat(LimitCheck.of(cp, d, BigDecimal.ZERO).breached).isTrue()
        }
    }

    @Nested
    inner class InterestAndCalendar {
        @Test
        fun `interest is ACT over 360`() {
            // 100 000 × 4.25 % × 30 / 360 = 354.1666… -> 354.17
            val d = placement(principal = "100000.00", rate = "4.25", maturity = MONDAY.plusDays(30))
            assertThat(d.days).isEqualTo(30)
            assertThat(d.interest).isEqualByComparingTo("354.17")
        }

        @Test
        fun `a year of 365 days earns more than the nominal rate under ACT-360`() {
            val i = DayCount.act360Interest(BigDecimal("1000000"), BigDecimal("3.60"), MONDAY, MONDAY.plusDays(365))
            assertThat(i).isEqualByComparingTo("36500.00")
        }

        @Test
        fun `overnight from a Friday matures on Monday - no holiday calendar`() {
            assertThat(DayCount.nextBusinessDay(FRIDAY)).isEqualTo(LocalDate.parse("2026-09-28"))
            assertThat(DayCount.nextBusinessDay(MONDAY)).isEqualTo(LocalDate.parse("2026-09-22"))
        }

        @Test
        fun `the ČNB facility is always overnight, CZK, facing ČNB`() {
            val d = placement(product = ProductType.CNB_DEPOSIT_FACILITY, counterparty = "CNB", maturity = null)
            assertThat(d.maturityDate).isEqualTo(LocalDate.parse("2026-09-22"))
            assertThatThrownBy {
                placement(product = ProductType.CNB_DEPOSIT_FACILITY, counterparty = "CNB", currency = "EUR")
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy {
                placement(product = ProductType.CNB_DEPOSIT_FACILITY, counterparty = "SIMBK-A")
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { placement(counterparty = "CNB") }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `invalid terms are rejected at draft`() {
            assertThatThrownBy { placement(principal = "0") }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { placement(principal = "1.001") }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { placement(currency = "USD") }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { placement(maturity = MONDAY) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { placement(rate = "-0.1") }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
