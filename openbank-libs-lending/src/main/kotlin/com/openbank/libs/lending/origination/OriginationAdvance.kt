// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.origination

/**
 * Forward drive along the canonical origination path (ADR-0211 D1). The transition
 * graph allows skipping the optional states; the pinned compliance pack decides which
 * of them are mandatory for a given application (ADR-0212), so the next state is a
 * pure function of the current state and the pack's mandatory set. Advancement stops
 * at [OriginationState.READY_TO_DISBURSE] — booking the loan is the disburse use
 * case, never an advance.
 *
 * Nor is leaving a decision state ([DECISION_STATES]) an advance: the way out of
 * [OriginationState.FOUR_EYES] is a decision recorded by a second person
 * ([FourEyesDecision]), so the forward drive has no next state there.
 */
object OriginationAdvance {

    private val OPTIONAL_STATES: Set<OriginationState> = setOf(
        OriginationState.DOCS_REQUIRED,
        OriginationState.REFLECTION_PERIOD,
    )

    /** States left only through an explicit decision, never through the generic forward drive. */
    val DECISION_STATES: Set<OriginationState> = setOf(OriginationState.FOUR_EYES)

    fun requiresDecision(state: OriginationState): Boolean = state in DECISION_STATES

    /**
     * States that exist only downstream of a four-eyes decision. An application in one of them must
     * carry a recorded decider; one that does not was never decided, and is neither advanced nor
     * disbursed (fail closed).
     */
    val POST_DECISION_STATES: Set<OriginationState> = setOf(
        OriginationState.OFFERED,
        OriginationState.AWAITING_SIGNATURE,
        OriginationState.SIGNED,
        OriginationState.REFLECTION_PERIOD,
        OriginationState.READY_TO_DISBURSE,
    )

    fun requiresRecordedDecision(state: OriginationState): Boolean = state in POST_DECISION_STATES

    private val FORWARD_PATH: List<OriginationState> = listOf(
        OriginationState.DRAFT,
        OriginationState.SUBMITTED,
        OriginationState.KYC_PENDING,
        OriginationState.DOCS_REQUIRED,
        OriginationState.ASSESSMENT,
        OriginationState.DECISION_PENDING,
        OriginationState.FOUR_EYES,
        OriginationState.OFFERED,
        OriginationState.AWAITING_SIGNATURE,
        OriginationState.SIGNED,
        OriginationState.REFLECTION_PERIOD,
        OriginationState.READY_TO_DISBURSE,
    )

    /**
     * The next forward state after [current], skipping optional states absent from
     * [mandatorySteps]; null when there is no forward drive (terminal,
     * [OriginationState.READY_TO_DISBURSE], or a decision state).
     */
    fun nextState(current: OriginationState, mandatorySteps: Set<OriginationState>): OriginationState? {
        if (requiresDecision(current)) return null
        val index = FORWARD_PATH.indexOf(current)
        if (index < 0) return null
        return FORWARD_PATH.subList(index + 1, FORWARD_PATH.size)
            .firstOrNull { it !in OPTIONAL_STATES || it in mandatorySteps }
    }
}
