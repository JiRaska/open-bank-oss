// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.origination

/**
 * ADR-0269 rule 3 — one credit journey, three product shapes.
 *
 * The [OriginationState] machine is shared and unchanged: unsecured, secured and revolving credit
 * all move through the same states, in the same order, under the same guards. What differs between
 * them is *which steps a customer has to complete*, and that difference lives in the requirement
 * list rather than in a second state machine.
 *
 * Three parallel origination pipelines would mean three audit trails, three sets of reason codes,
 * and three places for the ADR-0269 rule-2 suppression check to be forgotten — it only has to be
 * forgotten once to matter. The variance is genuinely in the paperwork, so it is modelled as
 * paperwork.
 */
enum class CreditProductKind {
    /** Cash loan. Decision in minutes, one disbursement, fixed schedule. */
    UNSECURED,

    /** Mortgage or car loan. Collateral, valuation, insurance, staged drawdowns, weeks not minutes. */
    SECURED,

    /** Overdraft, credit card, instalment limit. No disbursement at all — the limit is activated. */
    REVOLVING,
}

/** What the bank still needs from (or about) the applicant before the journey can move on. */
enum class CreditRequirementStatus {
    /** Not supplied yet. The customer's move. */
    TODO,

    /** Supplied and being checked. Nobody's move; the app shows a wait, not a button. */
    IN_REVIEW,

    /** Accepted. */
    SATISFIED,

    /** Rejected — [CreditRequirement.reason] says why, because "rejected" alone is unactionable. */
    REJECTED,
}

/**
 * One outstanding item. [code] is a stable machine key the client maps to its own copy; [reason] is
 * populated for [CreditRequirementStatus.REJECTED] and carries the checker's words.
 *
 * [completedByCustomer] separates "you must do this" from "we are doing this": a valuation is a
 * requirement of the journey but not a task for the applicant, and rendering it as one produces a
 * customer staring at a step they cannot action.
 */
data class CreditRequirement(
    val code: String,
    val status: CreditRequirementStatus,
    val completedByCustomer: Boolean,
    val reason: String? = null,
) {
    init {
        require(code.isNotBlank()) { "requirement code must not be blank" }
        require(status != CreditRequirementStatus.REJECTED || !reason.isNullOrBlank()) {
            "a rejected requirement must carry a reason"
        }
    }

    val outstanding: Boolean
        get() = status == CreditRequirementStatus.TODO || status == CreditRequirementStatus.REJECTED
}

/** How one journey step stands relative to where the application actually is. */
enum class CreditStepStatus { DONE, CURRENT, UPCOMING }

/** A customer-facing step: a stable [code] plus its position in this application's own journey. */
data class CreditJourneyStep(val code: String, val status: CreditStepStatus)

/**
 * The whole customer-readable view of an application. The client renders THIS and derives nothing:
 * no local step advancement, no inferred approval, no success state ahead of the server.
 *
 * [outcomeReasonCode] is non-null only in a terminal, non-disbursed state; it is the decision
 * engine's reason code (ADR-0213), never free text composed here.
 */
data class CreditJourneyView(
    val productKind: CreditProductKind,
    val state: OriginationState,
    val steps: List<CreditJourneyStep>,
    val requirements: List<CreditRequirement>,
    val outcomeReasonCode: String? = null,
) {
    /** What the customer must act on now. Empty is a meaningful answer: "nothing, wait for us". */
    val awaitingCustomer: List<CreditRequirement>
        get() = requirements.filter { it.outstanding && it.completedByCustomer }
}

/**
 * Builds [CreditJourneyView] from server state. Pure — no clock, no I/O, no persistence — so the
 * whole per-product shape is exercisable from a unit test rather than from a running journey.
 */
object CreditJourneyProjection {

    /**
     * The step sequence per product kind, in customer-facing order.
     *
     * These are STEPS, not states: several origination states collapse into one thing a customer
     * recognises ("we are assessing"), and one state can be invisible to them entirely. The mapping
     * from state to step is [stepOfState] below; a state absent from that map simply does not move
     * the customer's progress bar, which is the honest rendering of an internal-only transition.
     */
    private val UNSECURED_STEPS = listOf(STEP_APPLY, STEP_ASSESS, STEP_OFFER, STEP_SIGN, STEP_DISBURSE)

    /**
     * Secured credit inserts collateral work between offer and signature and replaces the single
     * disbursement with staged drawdowns. Several of these steps complete OUTSIDE the app (a lien
     * registration is not a mobile flow); they appear anyway, because a journey that hides the steps
     * it cannot host leaves the customer with weeks of unexplained silence.
     */
    private val SECURED_STEPS =
        listOf(STEP_APPLY, STEP_ASSESS, STEP_OFFER, STEP_COLLATERAL, STEP_SIGN, STEP_DRAWDOWN)

    /** Revolving credit has no disbursement: the limit is switched on. */
    private val REVOLVING_STEPS = listOf(STEP_APPLY, STEP_ASSESS, STEP_OFFER, STEP_SIGN, STEP_ACTIVATE_LIMIT)

    /** Which step each origination state belongs to. States not listed leave progress untouched. */
    private val STATE_TO_STEP: Map<OriginationState, String> = mapOf(
        OriginationState.DRAFT to STEP_APPLY,
        OriginationState.SUBMITTED to STEP_APPLY,
        OriginationState.KYC_PENDING to STEP_ASSESS,
        OriginationState.DOCS_REQUIRED to STEP_ASSESS,
        OriginationState.ASSESSMENT to STEP_ASSESS,
        OriginationState.DECISION_PENDING to STEP_ASSESS,
        OriginationState.FOUR_EYES to STEP_ASSESS,
        OriginationState.OFFERED to STEP_OFFER,
        OriginationState.AWAITING_SIGNATURE to STEP_SIGN,
        OriginationState.SIGNED to STEP_SIGN,
        OriginationState.REFLECTION_PERIOD to STEP_SIGN,
    )

    fun stepsFor(productKind: CreditProductKind): List<String> = when (productKind) {
        CreditProductKind.UNSECURED -> UNSECURED_STEPS
        CreditProductKind.SECURED -> SECURED_STEPS
        CreditProductKind.REVOLVING -> REVOLVING_STEPS
    }

    fun project(
        productKind: CreditProductKind,
        state: OriginationState,
        requirements: List<CreditRequirement> = emptyList(),
        outcomeReasonCode: String? = null,
    ): CreditJourneyView {
        val order = stepsFor(productKind)
        val currentStep = stepOfState(productKind, state)
        val currentIndex = order.indexOf(currentStep)
        val steps = order.mapIndexed { index, code ->
            CreditJourneyStep(code, statusAt(index, currentIndex, state))
        }
        return CreditJourneyView(
            productKind = productKind,
            state = state,
            steps = steps,
            requirements = requirements,
            // A reason code belongs to a refusal, never to a live or completed journey. Carrying one
            // on a DISBURSED application would render as "approved, because: declined".
            outcomeReasonCode = outcomeReasonCode?.takeIf { state in REFUSED_STATES },
        )
    }

    /**
     * The final step of a fully-completed journey is DONE, everything before the current step is
     * DONE, the current step is CURRENT, the rest UPCOMING.
     *
     * A refused journey (declined, withdrawn, expired) freezes: the steps behind it stay DONE and
     * nothing becomes CURRENT, because there is no next thing for the customer to do. Rendering a
     * live-looking CURRENT step on a declined application is how a customer waits for a decision
     * that already happened.
     */
    private fun statusAt(index: Int, currentIndex: Int, state: OriginationState): CreditStepStatus = when {
        state == OriginationState.DISBURSED -> CreditStepStatus.DONE
        state in REFUSED_STATES -> if (index < currentIndex) CreditStepStatus.DONE else CreditStepStatus.UPCOMING
        currentIndex < 0 -> CreditStepStatus.UPCOMING
        index < currentIndex -> CreditStepStatus.DONE
        index == currentIndex -> CreditStepStatus.CURRENT
        else -> CreditStepStatus.UPCOMING
    }

    /**
     * The step a state belongs to. READY_TO_DISBURSE and DISBURSED land on the product's own final
     * step, which is why they are resolved here rather than in [STATE_TO_STEP] — the same state
     * means "money on its way" for a cash loan, "first tranche" for a mortgage and "limit going
     * live" for an overdraft, and only the product kind can tell them apart.
     */
    private fun stepOfState(productKind: CreditProductKind, state: OriginationState): String? = when (state) {
        OriginationState.READY_TO_DISBURSE, OriginationState.DISBURSED -> stepsFor(productKind).last()
        // A refused journey's frozen position: the step it never got past.
        OriginationState.DECLINED, OriginationState.EXPIRED -> STEP_OFFER
        OriginationState.WITHDRAWN -> STEP_APPLY
        else -> STATE_TO_STEP[state]
    }

    private val REFUSED_STATES: Set<OriginationState> = setOf(
        OriginationState.DECLINED,
        OriginationState.WITHDRAWN,
        OriginationState.EXPIRED,
    )
}

// Stable step codes. The client maps these to its own copy; they are keys, never display strings.
const val STEP_APPLY = "APPLY"
const val STEP_ASSESS = "ASSESS"
const val STEP_OFFER = "OFFER"
const val STEP_COLLATERAL = "COLLATERAL"
const val STEP_SIGN = "SIGN"
const val STEP_DISBURSE = "DISBURSE"
const val STEP_DRAWDOWN = "DRAWDOWN"
const val STEP_ACTIVATE_LIMIT = "ACTIVATE_LIMIT"
