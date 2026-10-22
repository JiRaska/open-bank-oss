// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.cashflow

import com.openbank.libs.domain.calendar.BusinessDayConvention
import com.openbank.libs.finance.calendar.Calendars
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.treasury.domain.DealFixtures
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.DayCountConvention
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.FourEyesViolationException
import com.openbank.treasury.domain.model.LegRate
import com.openbank.treasury.domain.model.LegSpec
import com.openbank.treasury.domain.model.LegacyLegs
import com.openbank.treasury.domain.model.PayReceive
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.RateType
import com.openbank.treasury.domain.model.RollDirection
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CashFlowLedgerTest {
    private val now = Instant.parse("2026-10-19T08:00:00Z")
    private val backOffice = Actor("bo-1", ActorType.HUMAN)
    private val approver = Actor("approver-1", ActorType.HUMAN)
    private val agent = Actor("agent:treasury-assistant", ActorType.AI_AGENT)
    private val dealId = UUID.randomUUID()

    private fun ctx(version: Int = 1, roll: BusinessDayConvention = BusinessDayConvention.UNADJUSTED) = ScheduleContext(
        dealId = dealId,
        scheduleVersion = version,
        calendarFor = { Calendars.forCurrencies(it.currency) },
        fixingCalendar = { null },
        fixingLag = { 2 },
        roll = roll,
        rollDirection = RollDirection.FROM_END,
        newId = UUID::randomUUID,
    )

    @Nested
    inner class LegacyProductsUnchanged {
        @Test
        fun `a placement's ledger is principal out, principal and ACT-360 interest in - the deal's own figure`() {
            val deal = DealFixtures.placement()
            val legs = LegacyLegs.derive(deal)
            val flows = ScheduleAdapter.generate(legs, ctx())
            assertThat(flows.map { Triple(it.kind, it.direction, it.paymentDate) }).containsExactlyInAnyOrder(
                Triple(CashFlowKind.NOTIONAL_EXCHANGE, FlowDirection.OUT, deal.valueDate),
                Triple(CashFlowKind.INTEREST, FlowDirection.IN, deal.maturityDate),
                Triple(CashFlowKind.PRINCIPAL, FlowDirection.IN, deal.maturityDate),
            )
            assertThat(flows.single { it.kind == CashFlowKind.INTEREST }.amount).isEqualByComparingTo(deal.interest)
            assertThat(flows).allMatch { it.state == CashFlowState.FIXED && it.rateSource == RateSource.FIXED }
            assertThat(flows.single { it.kind == CashFlowKind.INTEREST }.rate).isEqualByComparingTo(deal.rate)
        }

        @Test
        fun `the engine's interest equals the deal's ACT-360 interest over a grid of principals, rates and tenors`() {
            val principals = listOf("0.01", "1.00", "999.99", "1000000.00", "123456789.12", "2000000000.00")
            val rates = listOf("0", "0.001", "1.25", "3.60", "4.25", "7.777777", "99.999999")
            val tenors = listOf(1L, 2L, 3L, 7L, 30L, 91L, 181L, 365L, 366L)
            principals.forEach { p ->
                rates.forEach { r ->
                    tenors.forEach { days ->
                        val deal = DealFixtures.placement().copy(
                            principal = BigDecimal(p),
                            rate = BigDecimal(r),
                            maturityDate = DealFixtures.placement().valueDate.plusDays(days),
                        )
                        val interest = ScheduleAdapter.generate(LegacyLegs.derive(deal), ctx())
                            .single { it.kind == CashFlowKind.INTEREST }.amount
                        assertThat(interest).describedAs("$p @ $r for $days days").isEqualByComparingTo(deal.interest)
                    }
                }
            }
        }

        @Test
        fun `a borrowing is the mirror image`() {
            val deal = DealFixtures.placement(product = ProductType.MM_BORROWING)
            val flows = ScheduleAdapter.generate(LegacyLegs.derive(deal), ctx())
            assertThat(
                flows.map {
                    it.direction
                },
            ).containsExactly(FlowDirection.IN, FlowDirection.OUT, FlowDirection.OUT)
        }

        @Test
        fun `an FX spot is two exchanges on the value date, nothing accrues`() {
            val deal = DealFixtures.fxSpot()
            val flows = ScheduleAdapter.generate(LegacyLegs.derive(deal), ctx())
            assertThat(flows).hasSize(2)
            assertThat(flows).allMatch { it.kind == CashFlowKind.NOTIONAL_EXCHANGE && it.paymentDate == deal.valueDate }
            assertThat(flows.map { it.currency }.toSet()).containsExactlyInAnyOrder(deal.currency, Deal.CZK)
            assertThat(flows.single { it.currency == Deal.CZK }.amount).isEqualByComparingTo(deal.fx!!.counterAmount)
        }
    }

    @Nested
    inner class LegDescribedProducts {
        private val start = LocalDate.parse("2026-11-02")
        private val fixed = LegSpec(
            PayReceive.PAY, "CZK", BigDecimal("10000000.00"), start, start.plusYears(1), DayCountConvention.ACT_360,
            LegRate.fixed(
                BigDecimal("4.00"),
            ),
            accrualFrequency = Frequency.QUARTERLY, paymentFrequency = Frequency.QUARTERLY,
        )
        private val floating = fixed.copy(
            payReceive = PayReceive.RECEIVE,
            rate = LegRate(
                RateType.FLOATING,
                index = "PRIBOR_3M",
                spread = BigDecimal("0.10"),
                resetFrequency = Frequency.QUARTERLY,
            ),
        )

        @Test
        fun `an IRS fixed leg is four FIXED coupons, the floating leg four PROJECTED rows awaiting fixings`() {
            val flows = ScheduleAdapter.generate(
                listOf(fixed, floating),
                ctx(roll = BusinessDayConvention.MODIFIED_FOLLOWING),
            )
            val fixedRows = flows.filter { it.legIndex == 0 && it.kind == CashFlowKind.INTEREST }
            val floatRows = flows.filter { it.legIndex == 1 && it.kind == CashFlowKind.INTEREST }
            assertThat(fixedRows).hasSize(4).allMatch {
                it.state == CashFlowState.FIXED &&
                    it.direction == FlowDirection.OUT
            }
            assertThat(floatRows).hasSize(4).allMatch {
                it.state == CashFlowState.PROJECTED &&
                    it.amount == null &&
                    it.fixingDate != null &&
                    it.direction == FlowDirection.IN
            }
            // Fixing lag 2 business days before each accrual start.
            assertThat(floatRows.minBy { it.periodIndex }.fixingDate).isEqualTo(LocalDate.parse("2026-10-29"))
            assertThat(flows.map { it.paymentDate }).allMatch { Calendars.forCurrencies("CZK").isBusinessDay(it) }
        }

        @Test
        fun `a rate until first fixing makes the first floating period FIXED`() {
            val leg = floating.copy(rate = floating.rate.copy(rateUntilFirstFixing = BigDecimal("3.50")))
            val rows = ScheduleAdapter.generate(listOf(leg), ctx()).filter {
                it.kind == CashFlowKind.INTEREST
            }.sortedBy { it.periodIndex }
            assertThat(rows.first().rateSource).isEqualTo(RateSource.RATE_UNTIL_FIRST_FIXING)
            assertThat(rows.first().state).isEqualTo(CashFlowState.FIXED)
            assertThat(rows.drop(1)).allMatch { it.state == CashFlowState.PROJECTED }
        }

        @Test
        fun `a leg the engine cannot express is a typed refusal, not a guess`() {
            val bad = floating.copy(rate = floating.rate.copy(resetFrequency = Frequency.MONTHLY))
            assertThatThrownBy { ScheduleAdapter.generate(listOf(bad), ctx()) }
                .isInstanceOf(ScheduleEngineUnavailableException::class.java)
        }
    }

    @Nested
    inner class Overrides {
        private val flows = ScheduleAdapter.generate(LegacyLegs.derive(DealFixtures.placement()), ctx())
        private val interest = flows.single { it.kind == CashFlowKind.INTEREST }

        private fun proposed(by: Actor = backOffice) = CashFlowOverride.propose(
            UUID.randomUUID(),
            interest,
            interest.paymentDate.plusDays(1),
            null,
            "holiday not modelled",
            by,
            now,
        )

        @Test
        fun `the proposer cannot approve or reject their own override`() {
            assertThatThrownBy {
                proposed().approve(backOffice, now)
            }.isInstanceOf(FourEyesViolationException::class.java)
            assertThatThrownBy {
                proposed().reject(backOffice, "no", now)
            }.isInstanceOf(FourEyesViolationException::class.java)
        }

        @Test
        fun `an AI agent may propose but never decide, even on its own proposal`() {
            val byAgent = proposed(agent)
            assertThat(byAgent.status).isEqualTo(OverrideStatus.PENDING)
            assertThatThrownBy { byAgent.approve(agent, now) }.isInstanceOf(ActorNotPermittedException::class.java)
            assertThatThrownBy { proposed().approve(agent, now) }.isInstanceOf(ActorNotPermittedException::class.java)
        }

        @Test
        fun `a service account may not even propose`() {
            assertThatThrownBy { proposed(Actor("service-account-x", ActorType.SERVICE)) }
                .isInstanceOf(ActorNotPermittedException::class.java)
        }

        @Test
        fun `an approved override keeps the original beside it and marks the flow manual`() {
            val o = proposed().approve(approver, now)
            val changed = interest.withOverride(o)
            assertThat(changed.manual).isTrue()
            assertThat(changed.paymentDate).isEqualTo(interest.paymentDate.plusDays(1))
            assertThat(changed.originalPaymentDate).isEqualTo(interest.paymentDate)
            assertThat(changed.originalAmount).isEqualByComparingTo(interest.amount)
            assertThatThrownBy { interest.withOverride(proposed()) }.isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `an override needs a change and a reason, and a decided one is final`() {
            assertThatThrownBy {
                CashFlowOverride.propose(UUID.randomUUID(), interest, null, null, "r", backOffice, now)
            }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy {
                CashFlowOverride.propose(UUID.randomUUID(), interest, null, BigDecimal.ONE, " ", backOffice, now)
            }
                .isInstanceOf(IllegalArgumentException::class.java)
            val decided = proposed().approve(approver, now)
            assertThatThrownBy {
                decided.approve(Actor("approver-2", ActorType.HUMAN), now)
            }.isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `a DUE or later flow needs a correction, not an override`() {
            assertThatThrownBy {
                CashFlowOverride.propose(
                    UUID.randomUUID(),
                    interest.copy(state = CashFlowState.DUE),
                    null,
                    BigDecimal.ONE,
                    "r",
                    backOffice,
                    now,
                )
            }.isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `regeneration carries an approved override onto the new flow when the leg is unchanged`() {
            val o = proposed().approve(approver, now)
            val next = ScheduleAdapter.generate(LegacyLegs.derive(DealFixtures.placement()), ctx(version = 2))
            val r = ScheduleRegeneration.regenerate(next, listOf(o), 1, UUID::randomUUID, now)
            assertThat(r.conflicts).isEmpty()
            val carried = r.carried.single()
            val target = r.flows.single { it.kind == CashFlowKind.INTEREST }
            assertThat(carried.flowId).isEqualTo(target.flowId).isNotEqualTo(interest.flowId)
            assertThat(target.manual).isTrue()
            assertThat(target.paymentDate).isEqualTo(interest.paymentDate.plusDays(1))
        }

        @Test
        fun `regeneration after the leg changed surfaces a conflict and never re-applies or drops the override`() {
            val o = proposed().approve(approver, now)
            val changedDeal = DealFixtures.placement().copy(rate = BigDecimal("5.00"))
            val next = ScheduleAdapter.generate(LegacyLegs.derive(changedDeal), ctx(version = 2))
            val r = ScheduleRegeneration.regenerate(next, listOf(o), 1, UUID::randomUUID, now)
            assertThat(r.carried).isEmpty()
            assertThat(r.conflicts).singleElement().satisfies({
                assertThat(it.overrideId).isEqualTo(o.overrideId)
                assertThat(it.reason).contains("leg spec")
                assertThat(it.fromScheduleVersion).isEqualTo(1)
                assertThat(it.toScheduleVersion).isEqualTo(2)
            })
            assertThat(r.flows).noneMatch { it.manual }
        }

        @Test
        fun `a pending or rejected override is not carried`() {
            val r = ScheduleRegeneration.regenerate(
                flows,
                listOf(proposed(), proposed().reject(approver, "no", now)),
                1,
                UUID::randomUUID,
                now,
            )
            assertThat(r.carried).isEmpty()
            assertThat(r.conflicts).isEmpty()
        }
    }
}
