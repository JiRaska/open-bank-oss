// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.origination

import com.openbank.libs.lending.origination.OriginationState.ASSESSMENT
import com.openbank.libs.lending.origination.OriginationState.AWAITING_SIGNATURE
import com.openbank.libs.lending.origination.OriginationState.DECISION_PENDING
import com.openbank.libs.lending.origination.OriginationState.DECLINED
import com.openbank.libs.lending.origination.OriginationState.DISBURSED
import com.openbank.libs.lending.origination.OriginationState.DOCS_REQUIRED
import com.openbank.libs.lending.origination.OriginationState.DRAFT
import com.openbank.libs.lending.origination.OriginationState.EXPIRED
import com.openbank.libs.lending.origination.OriginationState.FOUR_EYES
import com.openbank.libs.lending.origination.OriginationState.KYC_PENDING
import com.openbank.libs.lending.origination.OriginationState.OFFERED
import com.openbank.libs.lending.origination.OriginationState.READY_TO_DISBURSE
import com.openbank.libs.lending.origination.OriginationState.REFLECTION_PERIOD
import com.openbank.libs.lending.origination.OriginationState.SIGNED
import com.openbank.libs.lending.origination.OriginationState.SUBMITTED
import com.openbank.libs.lending.origination.OriginationState.WITHDRAWN

/**
 * Explicit, deterministic transition policy for loan origination (ADR-0211 D1), in the
 * ADR-0045 [com.openbank.libs.domain.case.CaseTransitionPolicy] pattern: the allowed
 * targets are data, validation is a pure function, and service-level concerns (maker !=
 * checker, pack-mandated waits) attach as [OriginationGuard] hooks keyed by transition.
 *
 * The graph is fixed in code for every jurisdiction — a compliance pack (ADR-0212)
 * parameterises *which optional states are mandatory* and *how long waits last*, never
 * the shape of the graph itself.
 */
data class OriginationTransitionPolicy(
    val allowedTransitions: Map<OriginationState, Set<OriginationState>>,
    val guards: Map<OriginationTransitionKey, List<OriginationGuard>> = emptyMap(),
) {
    fun isAllowed(from: OriginationState, to: OriginationState): Boolean =
        allowedTransitions[from].orEmpty().contains(to)

    fun allowedTargets(from: OriginationState): Set<OriginationState> = allowedTransitions[from].orEmpty()

    fun guardsFor(key: OriginationTransitionKey): List<OriginationGuard> = guards[key].orEmpty()

    companion object {
        /**
         * The canonical origination graph (ADR-0211 D1). Terminal states have no
         * outgoing transitions. [REFLECTION_PERIOD] is reachable but skippable — the
         * pinned pack decides, per application, which edge is taken.
         */
        fun standard(): OriginationTransitionPolicy = OriginationTransitionPolicy(
            allowedTransitions = mapOf(
                DRAFT to setOf(SUBMITTED, WITHDRAWN),
                SUBMITTED to setOf(KYC_PENDING, WITHDRAWN, EXPIRED),
                KYC_PENDING to setOf(DOCS_REQUIRED, ASSESSMENT, WITHDRAWN, DECLINED, EXPIRED),
                DOCS_REQUIRED to setOf(ASSESSMENT, WITHDRAWN, DECLINED, EXPIRED),
                ASSESSMENT to setOf(DECISION_PENDING, DOCS_REQUIRED, WITHDRAWN, DECLINED, EXPIRED),
                DECISION_PENDING to setOf(FOUR_EYES, DECLINED, WITHDRAWN, EXPIRED),
                FOUR_EYES to setOf(OFFERED, DECLINED, WITHDRAWN, EXPIRED),
                OFFERED to setOf(AWAITING_SIGNATURE, DECLINED, WITHDRAWN, EXPIRED),
                AWAITING_SIGNATURE to setOf(SIGNED, WITHDRAWN, EXPIRED),
                SIGNED to setOf(REFLECTION_PERIOD, READY_TO_DISBURSE, EXPIRED),
                REFLECTION_PERIOD to setOf(READY_TO_DISBURSE, EXPIRED),
                READY_TO_DISBURSE to setOf(DISBURSED, EXPIRED),
            ),
        )
    }
}

/** Stable key used to attach policy guards to a specific origination transition. */
data class OriginationTransitionKey(val from: OriginationState, val to: OriginationState)

/** Optional transition guard hook for service-level validation (maker != checker, pack waits, …). */
fun interface OriginationGuard {
    fun evaluate(transition: OriginationTransition): OriginationGuardFailure?
}

/** Describes why a transition was rejected by a guard. */
data class OriginationGuardFailure(val reason: String)
