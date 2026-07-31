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
 */
object OriginationAdvance {

    private val OPTIONAL_STATES: Set<OriginationState> = setOf(
        OriginationState.DOCS_REQUIRED,
        OriginationState.REFLECTION_PERIOD,
    )

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
     * [mandatorySteps]; null when there is no forward drive (terminal or
     * [OriginationState.READY_TO_DISBURSE]).
     */
    fun nextState(current: OriginationState, mandatorySteps: Set<OriginationState>): OriginationState? {
        val index = FORWARD_PATH.indexOf(current)
        if (index < 0) return null
        return FORWARD_PATH.subList(index + 1, FORWARD_PATH.size)
            .firstOrNull { it !in OPTIONAL_STATES || it in mandatorySteps }
    }
}
