// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.payment

import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.CashFlowKind
import com.openbank.treasury.domain.cashflow.CashFlowState
import com.openbank.treasury.domain.cashflow.FlowDirection
import com.openbank.treasury.domain.cashflow.RateSource
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.FourEyesViolationException
import com.openbank.treasury.domain.payment.FundingCheck
import com.openbank.treasury.domain.payment.Payment
import com.openbank.treasury.domain.payment.PaymentProposals
import com.openbank.treasury.domain.payment.PaymentStatus
import com.openbank.treasury.domain.payment.ProposalOutcome
import com.openbank.treasury.domain.payment.SsiSnapshot
import com.openbank.treasury.domain.reference.SettlementRail
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class PaymentsTest {
    private val at = Instant.parse("2026-10-16T08:00:00Z")
    private val day = LocalDate.parse("2026-10-20")
    private val deal = UUID.randomUUID()
    private val ssi = SsiSnapshot(SettlementRail.CERTIS, "CZ6508000000192000145399", null, 1)

    private fun flow(
        dir: FlowDirection,
        amount: String,
        state: CashFlowState = CashFlowState.FIXED,
        paymentId: String? = null,
    ) = CashFlow(
        flowId = UUID.randomUUID(),
        dealId = deal,
        scheduleVersion = 1,
        legIndex = 0,
        periodIndex = 0,
        kind = CashFlowKind.INTEREST,
        direction = dir,
        currency = "CZK",
        accrualStart = day.minusMonths(3),
        accrualEnd = day,
        fixingDate = null,
        paymentDate = day,
        notional = BigDecimal("1000000.00"),
        rate = BigDecimal("4"),
        rateSource = RateSource.FIXED,
        amount = BigDecimal(amount),
        state = state,
        legHash = "h",
        paymentId = paymentId,
    )

    private fun candidates(vararg fs: CashFlow) = fs.map {
        PaymentProposals.Candidate(it, "SIMBK-A", setOf("dana.dealer", "editor.erik"))
    }

    private fun propose(netting: Boolean, vararg fs: CashFlow, address: SsiSnapshot? = ssi) =
        PaymentProposals.propose(candidates(*fs), { _, _ -> netting }, { _, _ -> address }, { UUID.randomUUID() }, at)

    private fun proposed(netting: Boolean = false, vararg fs: CashFlow) =
        (propose(netting, *fs).single() as ProposalOutcome.Proposed).payment

    @Test
    fun `one payment per flow without netting, keyed by deal and flow`() {
        val out = flow(FlowDirection.OUT, "100.00")
        val p = proposed(false, out)
        assertThat(p.idempotencyKey).isEqualTo("treasury:$deal:${out.flowId}:payment")
        assertThat(p.direction).isEqualTo(FlowDirection.OUT)
        assertThat(propose(false, flow(FlowDirection.OUT, "1.00"), flow(FlowDirection.IN, "2.00"))).hasSize(2)
    }

    @Test
    fun `netting nets IN against OUT into one payment with a deterministic key over sorted flow ids`() {
        val a = flow(FlowDirection.OUT, "100.00")
        val b = flow(FlowDirection.IN, "30.00")
        val p = proposed(true, a, b)
        assertThat(p.amount).isEqualByComparingTo("70.00")
        assertThat(p.direction).isEqualTo(FlowDirection.OUT)
        assertThat(p.idempotencyKey).isEqualTo(proposed(true, b, a).idempotencyKey).startsWith("treasury:netted:")
        assertThat(propose(true, flow(FlowDirection.OUT, "5.00"), flow(FlowDirection.IN, "5.00")).single())
            .isInstanceOf(ProposalOutcome.NettedToZero::class.java)
    }

    @Test
    fun `an outgoing amount without an SSI is not proposed, and unfixed or already-paid flows are skipped`() {
        assertThat(propose(false, flow(FlowDirection.OUT, "1.00"), address = null).single())
            .isInstanceOf(ProposalOutcome.Unaddressable::class.java)
        assertThat(propose(false, flow(FlowDirection.OUT, "1.00", CashFlowState.PROJECTED))).isEmpty()
        assertThat(propose(false, flow(FlowDirection.OUT, "1.00", paymentId = "x"))).isEmpty()
    }

    @Test
    fun `release - the creator, a term author, an AI agent and a service are refused, another human releases`() {
        val p = proposed(false, flow(FlowDirection.OUT, "100.00"))
        assertThatThrownBy {
            p.release(Actor("dana.dealer", ActorType.HUMAN), at)
        }.isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy {
            p.release(Actor("editor.erik", ActorType.HUMAN), at)
        }.isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy { p.release(Actor("agent:treasury-drafter", ActorType.AI_AGENT), at) }
            .isInstanceOf(ActorNotPermittedException::class.java)
        assertThatThrownBy { p.release(Actor("service-account-x", ActorType.SERVICE), at) }
            .isInstanceOf(ActorNotPermittedException::class.java)
        val released = p.release(Actor("rita.releaser", ActorType.HUMAN), at)
        assertThat(released.status).isEqualTo(PaymentStatus.RELEASED)
        assertThatThrownBy {
            released.release(Actor("other", ActorType.HUMAN), at)
        }.isInstanceOf(IllegalStateException::class.java)
        val parked = released.railNotSupported("no bank-own path")
        assertThat(parked.status).isEqualTo(PaymentStatus.RAIL_NOT_SUPPORTED)
        // A released payment is never cancelled; only an independent human resolves it.
        assertThatThrownBy {
            parked.cancel(Actor("bert", ActorType.HUMAN), "oops")
        }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            parked.resolve(Actor("rita.releaser", ActorType.HUMAN), PaymentStatus.MANUALLY_SETTLED, "r", emptySet())
        }
            .isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy {
            parked.resolve(Actor("dana.dealer", ActorType.HUMAN), PaymentStatus.RETURNED, "r", setOf("dana.dealer"))
        }
            .isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy {
            parked.resolve(Actor("agent:x", ActorType.AI_AGENT), PaymentStatus.RETURNED, "r", emptySet())
        }
            .isInstanceOf(ActorNotPermittedException::class.java)
        assertThat(
            parked.resolve(Actor("anna", ActorType.HUMAN), PaymentStatus.RETURNED, "returned", emptySet()).status,
        )
            .isEqualTo(PaymentStatus.RETURNED)
        // Authors computed at release time win over the proposal-time cache.
        assertThatThrownBy { p.release(Actor("late.author", ActorType.HUMAN), at, setOf("late.author")) }
            .isInstanceOf(FourEyesViolationException::class.java)
    }

    @Test
    fun `an incoming payment is never released, only settled`() {
        val p = proposed(false, flow(FlowDirection.IN, "10.00"))
        assertThatThrownBy {
            p.release(Actor("rita.releaser", ActorType.HUMAN), at)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(p.settled("E2E-1").status).isEqualTo(PaymentStatus.SETTLED)
    }

    @Test
    fun `funding - a payment that takes the account negative net of same-day releases is exceeded`() {
        assertThat(FundingCheck.check(BigDecimal("100"), BigDecimal("30"), BigDecimal("70")).exceeded).isFalse()
        assertThat(FundingCheck.check(BigDecimal("100"), BigDecimal("30"), BigDecimal("70.01")).exceeded).isTrue()
    }

    @Test
    fun `bounds - a payment amount is positive with cents, and settles 1 to 500 flows`() {
        val p = proposed(false, flow(FlowDirection.OUT, "1.00"))
        assertThatThrownBy { p.copy(amount = BigDecimal("0.001")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { p.copy(nettedFlowIds = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            p.cancel(Actor("bert", ActorType.HUMAN), "")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Payment.MAX_FLOWS).isEqualTo(500)
    }
}
