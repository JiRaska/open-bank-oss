// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.case

/** Guard outcome for a requested transition. */
sealed interface CaseTransitionGuardResult {
    data object Allowed : CaseTransitionGuardResult

    data class Rejected(val reason: String) : CaseTransitionGuardResult
}

/** Result of applying a transition through the engine. */
sealed interface CaseTransitionResult {
    data class Applied(val newStatus: CaseStatus, val timelineEvent: CaseTimelineEvent) : CaseTransitionResult

    data class Rejected(val reason: String) : CaseTransitionResult
}

/**
 * Pure transition engine for reusable case workflows.
 *
 * The engine is deterministic: the outcome depends only on the provided policy and transition.
 */
class CaseTransitionEngine(private val policy: CaseTransitionPolicy = CaseTransitionPolicy.standard()) {
    fun guard(transition: CaseTransition): CaseTransitionGuardResult {
        if (!policy.isAllowed(transition.fromStatus, transition.toStatus)) {
            return CaseTransitionGuardResult.Rejected(
                "Transition from ${transition.fromStatus} to ${transition.toStatus} is not allowed",
            )
        }

        if (transition.actor.isBlank()) {
            return CaseTransitionGuardResult.Rejected("Actor must not be blank")
        }

        val blankMetadataKey = transition.metadata.keys.firstOrNull { it.isBlank() }
        if (blankMetadataKey != null) {
            return CaseTransitionGuardResult.Rejected("Metadata keys must not be blank")
        }

        val guardFailure = policy.guardsFor(transition)
            .asSequence()
            .mapNotNull { it.evaluate(transition) }
            .firstOrNull()

        return if (guardFailure == null) {
            CaseTransitionGuardResult.Allowed
        } else {
            CaseTransitionGuardResult.Rejected(guardFailure.reason)
        }
    }

    fun apply(transition: CaseTransition): CaseTransitionResult = when (val guardResult = guard(transition)) {
        CaseTransitionGuardResult.Allowed -> CaseTransitionResult.Applied(
            newStatus = transition.toStatus,
            timelineEvent = CaseTimelineEvent(
                caseId = transition.caseId,
                caseType = transition.caseType,
                fromStatus = transition.fromStatus,
                toStatus = transition.toStatus,
                reasonCode = transition.reasonCode,
                actor = transition.actor,
                occurredAt = transition.occurredAt,
                metadata = timelineMetadata(transition),
            ),
        )

        is CaseTransitionGuardResult.Rejected -> CaseTransitionResult.Rejected(guardResult.reason)
    }

    private fun timelineMetadata(transition: CaseTransition): Map<String, String> = linkedMapOf(
        "caseType" to transition.caseType.name,
        "fromStatus" to transition.fromStatus.name,
        "toStatus" to transition.toStatus.name,
        "reasonCode" to transition.reasonCode.name,
        "actor" to transition.actor,
    ).apply {
        putAll(transition.metadata.toSortedMap())
    }
}
