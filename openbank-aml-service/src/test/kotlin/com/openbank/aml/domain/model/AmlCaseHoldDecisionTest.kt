// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.model

import com.openbank.libs.governance.StaffIdentity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** ADR-0374 point 3 in the aggregate: one analyst alone cannot decide a held payment's case. */
class AmlCaseHoldDecisionTest {

    private val t0 = Instant.parse("2026-11-14T10:00:00Z")
    private val t1 = Instant.parse("2026-11-14T10:05:00Z")

    // alice and alice2 are two accounts of ONE person.
    private val alice = StaffIdentity("alice", "staff-a", strongAuth = true)
    private val alice2 = StaffIdentity("alice.second", "staff-a", strongAuth = true)
    private val bob = StaffIdentity("bob", "staff-b", strongAuth = true)
    private val carol = StaffIdentity("carol", "staff-c", strongAuth = true)

    private fun case(
        origin: CaseOrigin? = CaseOrigin.PAYMENT_HOLD_INTAKE,
        status: AmlCaseStatus = AmlCaseStatus.UNDER_REVIEW,
    ) = AmlCase(
        id = UUID.fromString("00000000-0000-4000-8000-00000000d001"),
        idempotencyKey = if (origin == CaseOrigin.PAYMENT_HOLD_INTAKE) "payment-hold:domestic:p1" else "rest-1",
        partyId = null,
        accountId = UUID.fromString("00000000-0000-4000-8000-00000000d002"),
        transactionId = null,
        customerReference = "ref",
        screeningType = ScreeningType.TRANSACTION_MONITORING,
        riskLevel = AmlRiskLevel.HIGH,
        status = status,
        alertCode = "PAYMENT_HELD",
        alertDetail = null,
        matchedEntity = null,
        decisionReason = null,
        assignedAnalyst = null,
        decidedBy = null,
        screenedAt = t0,
        decidedAt = null,
        createdAt = t0,
        updatedAt = t0,
        origin = origin,
    )

    @Test
    fun `no single-step transition reaches CLEARED or BLOCKED on a hold case`() {
        for (target in AmlCase.TERMINAL_STATUSES) {
            assertThatThrownBy { case().transitionTo(target, "r", null, "alice", t1) }
                .isInstanceOf(HoldDecisionFourEyesException::class.java)
                .hasMessageContaining("/hold-decision/proposal")
                .hasMessageContaining("/hold-decision/confirmation")
        }
    }

    @Test
    fun `a non-terminal step stays one analyst's`() {
        assertThat(
            case(
                status = AmlCaseStatus.OPEN,
            ).transitionTo(AmlCaseStatus.UNDER_REVIEW, null, "alice", "alice", t1).status,
        )
            .isEqualTo(AmlCaseStatus.UNDER_REVIEW)
        assertThat(
            case().transitionTo(AmlCaseStatus.ESCALATED, "senior", null, "alice", t1).status,
        ).isEqualTo(AmlCaseStatus.ESCALATED)
    }

    @Test
    fun `a proposal changes no status, and a different analyst's confirmation decides`() {
        for (outcome in HoldDisposition.entries) {
            val proposed = case().proposeHoldDecision(outcome, "  reviewed  ", alice, t0)
            assertThat(proposed.status).isEqualTo(AmlCaseStatus.UNDER_REVIEW)
            assertThat(
                proposed.holdProposal,
            ).isEqualTo(
                HoldDecisionProposal(outcome, "reviewed", "alice", t0, "staff-a", t0.plus(AmlCase.HOLD_PROPOSAL_TTL)),
            )

            val decided = proposed.confirmHoldDecision(outcome, "reviewed", bob, t1)
            assertThat(decided.status).isEqualTo(outcome.status)
            assertThat(decided.decidedBy).isEqualTo("bob")
            assertThat(decided.decisionReason).isEqualTo("reviewed")
            assertThat(decided.decidedAt).isEqualTo(t1)
            assertThat(decided.holdProposal!!.proposedBy).isEqualTo("alice")
        }
    }

    @Test
    fun `the proposer cannot confirm their own proposal`() {
        val proposed = case().proposeHoldDecision(HoldDisposition.CLEAR, "fine", alice, t0)
        assertThatThrownBy { proposed.confirmHoldDecision(HoldDisposition.CLEAR, "fine", alice, t1) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
            .hasMessageContaining("four-eyes")
    }

    @Test
    fun `a confirmation that does not restate the pending proposal is refused`() {
        val proposed = case().proposeHoldDecision(HoldDisposition.CLEAR, "fine", alice, t0)
        assertThatThrownBy { proposed.confirmHoldDecision(HoldDisposition.BLOCK, "fine", bob, t1) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
        assertThatThrownBy { proposed.confirmHoldDecision(HoldDisposition.CLEAR, "other reason", bob, t1) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
    }

    @Test
    fun `a confirmation without a proposal is refused`() {
        assertThatThrownBy { case().confirmHoldDecision(HoldDisposition.CLEAR, "fine", bob, t1) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
    }

    @Test
    fun `a decided case takes neither a proposal nor a confirmation`() {
        val decided = case().proposeHoldDecision(HoldDisposition.BLOCK, "listed", alice, t0)
            .confirmHoldDecision(HoldDisposition.BLOCK, "listed", bob, t1)
        assertThatThrownBy { decided.proposeHoldDecision(HoldDisposition.CLEAR, "x", carol, t1) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
        assertThatThrownBy { decided.confirmHoldDecision(HoldDisposition.BLOCK, "listed", carol, t1) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
    }

    @Test
    fun `a case the intake did not open keeps the single-step decision and has no proposal route`() {
        for (origin in listOf(null) + (CaseOrigin.entries - CaseOrigin.PAYMENT_HOLD_INTAKE)) {
            val other = case(origin = origin)
            assertThat(
                other.transitionTo(AmlCaseStatus.CLEARED, "fine", null, "alice", t1).status,
            ).isEqualTo(AmlCaseStatus.CLEARED)
            assertThatThrownBy { other.proposeHoldDecision(HoldDisposition.CLEAR, "fine", alice, t0) }
                .isInstanceOf(HoldDecisionFourEyesException::class.java)
        }
    }

    @Test
    fun `the proposer's second account cannot confirm`() {
        val proposed = case().proposeHoldDecision(HoldDisposition.CLEAR, "fine", alice, t0)
        assertThatThrownBy { proposed.confirmHoldDecision(HoldDisposition.CLEAR, "fine", alice2, t1) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
            .hasMessageContaining("same person")
    }

    @Test
    fun `no person id cannot propose or confirm, and no second factor cannot confirm`() {
        val anonymous = StaffIdentity("dave", null, strongAuth = true)
        assertThatThrownBy { case().proposeHoldDecision(HoldDisposition.CLEAR, "fine", anonymous, t0) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
        val proposed = case().proposeHoldDecision(HoldDisposition.CLEAR, "fine", alice, t0)
        assertThatThrownBy { proposed.confirmHoldDecision(HoldDisposition.CLEAR, "fine", anonymous, t1) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
        assertThatThrownBy {
            proposed.confirmHoldDecision(HoldDisposition.CLEAR, "fine", bob.copy(strongAuth = false), t1)
        }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
            .hasMessageContaining("second factor")
    }

    @Test
    fun `a pending proposal is not replaced - its proposer withdraws it, then anyone may propose`() {
        val proposed = case().proposeHoldDecision(HoldDisposition.CLEAR, "fine", alice, t0)
        assertThatThrownBy { proposed.proposeHoldDecision(HoldDisposition.BLOCK, "no", carol, t1) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
        assertThatThrownBy { proposed.withdrawHoldDecision(carol, t1) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
        val withdrawn = proposed.withdrawHoldDecision(alice2, t1)
        assertThat(withdrawn.holdProposal!!.withdrawnBy).isEqualTo("alice.second")
        assertThat(withdrawn.holdProposal!!.withdrawnAt).isEqualTo(t1)
        assertThatThrownBy { withdrawn.confirmHoldDecision(HoldDisposition.CLEAR, "fine", bob, t1) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
        val replaced = withdrawn.proposeHoldDecision(HoldDisposition.BLOCK, "no", carol, t1)
        assertThat(replaced.holdProposal!!.proposedBy).isEqualTo("carol")
        assertThat(replaced.holdProposal!!.withdrawnAt).isNull()
    }

    @Test
    fun `an expired proposal cannot be confirmed and may be replaced`() {
        val proposed = case().proposeHoldDecision(HoldDisposition.CLEAR, "fine", alice, t0)
        val late = t0.plus(AmlCase.HOLD_PROPOSAL_TTL)
        assertThatThrownBy { proposed.confirmHoldDecision(HoldDisposition.CLEAR, "fine", bob, late) }
            .isInstanceOf(HoldDecisionFourEyesException::class.java)
            .hasMessageContaining("expired")
        assertThat(proposed.proposeHoldDecision(HoldDisposition.BLOCK, "no", carol, late).holdProposal!!.proposedBy)
            .isEqualTo("carol")
    }

    @Test
    fun `a proposal of the V17 shape is inert - replaceable by anyone, never confirmable`() {
        for (legacy in listOf(
            HoldDecisionProposal(HoldDisposition.CLEAR, "fine", "alice", t0),
            HoldDecisionProposal(HoldDisposition.CLEAR, "fine", "alice", t0, proposerPersonId = "staff-a"),
            HoldDecisionProposal(HoldDisposition.CLEAR, "fine", "alice", t0, expiresAt = t0.plusSeconds(3600)),
        )) {
            val stuck = case().copy(holdProposal = legacy)
            assertThat(legacy.isOpenAt(stuck.id, t1)).isFalse()
            assertThatThrownBy { stuck.confirmHoldDecision(HoldDisposition.CLEAR, "fine", bob, t1) }
                .isInstanceOf(HoldDecisionFourEyesException::class.java)
            val fresh = stuck.proposeHoldDecision(HoldDisposition.BLOCK, "listed", carol, t1)
            assertThat(fresh.holdProposal!!.proposedBy).isEqualTo("carol")
            assertThat(fresh.confirmHoldDecision(HoldDisposition.BLOCK, "listed", bob, t1).status)
                .isEqualTo(AmlCaseStatus.BLOCKED)
        }
    }
}
