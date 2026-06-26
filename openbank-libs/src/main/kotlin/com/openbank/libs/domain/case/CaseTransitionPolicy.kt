// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.domain.case

/** Optional transition guard hook for policy-specific validation. */
fun interface CaseTransitionGuard {
    fun evaluate(transition: CaseTransition): CaseGuardFailure?
}

/** Describes why a transition was rejected by a guard. */
data class CaseGuardFailure(val reason: String)

/** Stable key used to attach policy guards to a specific status change. */
data class CaseTransitionKey(val fromStatus: CaseStatus, val toStatus: CaseStatus)

/**
 * Explicit transition policy with deterministic allowed targets and optional guard hooks.
 */
data class CaseTransitionPolicy(
    val allowedTransitions: Map<CaseStatus, Set<CaseStatus>>,
    val guards: Map<CaseTransitionKey, List<CaseTransitionGuard>> = emptyMap(),
) {
    fun isAllowed(fromStatus: CaseStatus, toStatus: CaseStatus): Boolean =
        allowedTransitions[fromStatus].orEmpty().contains(toStatus)

    fun guardsFor(transition: CaseTransition): List<CaseTransitionGuard> =
        guards[CaseTransitionKey(transition.fromStatus, transition.toStatus)].orEmpty()

    companion object {
        fun standard(): CaseTransitionPolicy = CaseTransitionPolicy(
            allowedTransitions = mapOf(
                CaseStatus.DRAFT to setOf(CaseStatus.OPEN, CaseStatus.CANCELLED),
                CaseStatus.OPEN to setOf(
                    CaseStatus.IN_REVIEW,
                    CaseStatus.WAITING_FOR_CUSTOMER,
                    CaseStatus.WAITING_FOR_EXTERNAL_PARTY,
                    CaseStatus.APPROVED,
                    CaseStatus.REJECTED,
                    CaseStatus.CANCELLED,
                ),
                CaseStatus.IN_REVIEW to setOf(
                    CaseStatus.OPEN,
                    CaseStatus.WAITING_FOR_CUSTOMER,
                    CaseStatus.WAITING_FOR_EXTERNAL_PARTY,
                    CaseStatus.APPROVED,
                    CaseStatus.REJECTED,
                    CaseStatus.CANCELLED,
                ),
                CaseStatus.WAITING_FOR_CUSTOMER to setOf(
                    CaseStatus.OPEN,
                    CaseStatus.IN_REVIEW,
                    CaseStatus.CANCELLED,
                ),
                CaseStatus.WAITING_FOR_EXTERNAL_PARTY to setOf(
                    CaseStatus.OPEN,
                    CaseStatus.IN_REVIEW,
                    CaseStatus.CANCELLED,
                ),
                CaseStatus.APPROVED to setOf(CaseStatus.CLOSED, CaseStatus.OPEN),
                CaseStatus.REJECTED to setOf(CaseStatus.CLOSED, CaseStatus.OPEN),
                CaseStatus.CLOSED to setOf(CaseStatus.OPEN),
                CaseStatus.CANCELLED to setOf(CaseStatus.OPEN),
            ),
        )
    }
}
