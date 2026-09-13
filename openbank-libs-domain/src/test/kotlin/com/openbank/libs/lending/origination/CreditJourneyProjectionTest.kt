// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.origination

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** ADR-0269 rule 3: one journey, three product shapes, and a view the client renders rather than infers. */
class CreditJourneyProjectionTest {

    private fun codes(view: CreditJourneyView) = view.steps.map { it.code }

    private fun statusOf(view: CreditJourneyView, code: String) = view.steps.first { it.code == code }.status

    // ── The three shapes ──────────────────────────────────────────────────────

    @Test
    fun `unsecured credit ends in a disbursement`() {
        val view = CreditJourneyProjection.project(CreditProductKind.UNSECURED, OriginationState.SUBMITTED)
        assertThat(codes(view)).containsExactly(STEP_APPLY, STEP_ASSESS, STEP_OFFER, STEP_SIGN, STEP_DISBURSE)
    }

    @Test
    fun `secured credit inserts collateral before signing and draws down in tranches`() {
        val view = CreditJourneyProjection.project(CreditProductKind.SECURED, OriginationState.SUBMITTED)
        assertThat(codes(view))
            .containsExactly(STEP_APPLY, STEP_ASSESS, STEP_OFFER, STEP_COLLATERAL, STEP_SIGN, STEP_DRAWDOWN)
    }

    @Test
    fun `revolving credit has no disbursement at all — the limit is activated`() {
        val view = CreditJourneyProjection.project(CreditProductKind.REVOLVING, OriginationState.SUBMITTED)
        assertThat(codes(view)).doesNotContain(STEP_DISBURSE, STEP_DRAWDOWN)
        assertThat(codes(view).last()).isEqualTo(STEP_ACTIVATE_LIMIT)
    }

    @Test
    fun `all three shapes share the same opening steps — the variance is later, in the paperwork`() {
        val opening = CreditProductKind.entries.map { CreditJourneyProjection.stepsFor(it).take(3) }
        assertThat(opening).allMatch { it == listOf(STEP_APPLY, STEP_ASSESS, STEP_OFFER) }
    }

    // ── Progress ──────────────────────────────────────────────────────────────

    @Test
    fun `assessment states collapse into one customer-visible step`() {
        val states = listOf(
            OriginationState.KYC_PENDING,
            OriginationState.DOCS_REQUIRED,
            OriginationState.ASSESSMENT,
            OriginationState.DECISION_PENDING,
            OriginationState.FOUR_EYES,
        )
        states.forEach { state ->
            val view = CreditJourneyProjection.project(CreditProductKind.UNSECURED, state)
            assertThat(statusOf(view, STEP_ASSESS)).describedAs(state.name).isEqualTo(CreditStepStatus.CURRENT)
            assertThat(statusOf(view, STEP_APPLY)).isEqualTo(CreditStepStatus.DONE)
        }
    }

    @Test
    fun `a disbursed application shows every step done`() {
        val view = CreditJourneyProjection.project(CreditProductKind.UNSECURED, OriginationState.DISBURSED)
        assertThat(view.steps.map { it.status }).containsOnly(CreditStepStatus.DONE)
    }

    @Test
    fun `READY_TO_DISBURSE lands on the product's own final step, not on a shared one`() {
        val revolving = CreditJourneyProjection.project(CreditProductKind.REVOLVING, OriginationState.READY_TO_DISBURSE)
        val secured = CreditJourneyProjection.project(CreditProductKind.SECURED, OriginationState.READY_TO_DISBURSE)
        assertThat(statusOf(revolving, STEP_ACTIVATE_LIMIT)).isEqualTo(CreditStepStatus.CURRENT)
        assertThat(statusOf(secured, STEP_DRAWDOWN)).isEqualTo(CreditStepStatus.CURRENT)
    }

    // ── Refusal must not look live ────────────────────────────────────────────

    @Test
    fun `a declined application has no CURRENT step — there is nothing left to wait for`() {
        val view = CreditJourneyProjection.project(
            CreditProductKind.UNSECURED,
            OriginationState.DECLINED,
            outcomeReasonCode = "DSTI_ABOVE_LIMIT",
        )
        assertThat(view.steps.map { it.status }).doesNotContain(CreditStepStatus.CURRENT)
        assertThat(view.outcomeReasonCode).isEqualTo("DSTI_ABOVE_LIMIT")
    }

    @Test
    fun `a reason code is dropped on a live or completed journey rather than rendered as a refusal`() {
        val disbursed = CreditJourneyProjection.project(
            CreditProductKind.UNSECURED,
            OriginationState.DISBURSED,
            outcomeReasonCode = "DSTI_ABOVE_LIMIT",
        )
        assertThat(disbursed.outcomeReasonCode).isNull()
    }

    // ── Requirements ──────────────────────────────────────────────────────────

    @Test
    fun `awaitingCustomer lists only outstanding items the customer can actually action`() {
        val view = CreditJourneyProjection.project(
            CreditProductKind.SECURED,
            OriginationState.OFFERED,
            requirements = listOf(
                CreditRequirement("TITLE_DEED", CreditRequirementStatus.TODO, completedByCustomer = true),
                CreditRequirement("VALUATION", CreditRequirementStatus.TODO, completedByCustomer = false),
                CreditRequirement("INCOME_PROOF", CreditRequirementStatus.SATISFIED, completedByCustomer = true),
                CreditRequirement("INSURANCE", CreditRequirementStatus.IN_REVIEW, completedByCustomer = true),
            ),
        )
        assertThat(view.awaitingCustomer.map { it.code }).containsExactly("TITLE_DEED")
    }

    @Test
    fun `a rejected requirement counts as outstanding and is handed back to the customer`() {
        val view = CreditJourneyProjection.project(
            CreditProductKind.SECURED,
            OriginationState.OFFERED,
            requirements = listOf(
                CreditRequirement("TITLE_DEED", CreditRequirementStatus.REJECTED, true, reason = "illegible scan"),
            ),
        )
        assertThat(view.awaitingCustomer.map { it.code }).containsExactly("TITLE_DEED")
    }

    @Test
    fun `a rejected requirement without a reason is refused at construction — unactionable by definition`() {
        assertThatThrownBy {
            CreditRequirement("TITLE_DEED", CreditRequirementStatus.REJECTED, completedByCustomer = true)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an empty awaitingCustomer is a real answer — nothing to do but wait`() {
        val view = CreditJourneyProjection.project(
            CreditProductKind.UNSECURED,
            OriginationState.ASSESSMENT,
            requirements = listOf(CreditRequirement("VALUATION", CreditRequirementStatus.TODO, false)),
        )
        assertThat(view.awaitingCustomer).isEmpty()
        assertThat(view.requirements).hasSize(1)
    }
}
