// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.origination

import java.time.Instant

/** Who commands a transition — machine transitions carry [SYSTEM], human ones [HUMAN] (ADR-0211 D3). */
enum class OriginationActorKind { HUMAN, SYSTEM }

/** A requested origination transition with its evidentiary payload (ADR-0214). */
data class OriginationTransition(
    val applicationId: String,
    val from: OriginationState,
    val to: OriginationState,
    val actor: String,
    val actorKind: OriginationActorKind,
    val reason: String,
    val occurredAt: Instant,
    val packVersion: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/** Result of applying a transition through the state machine. */
sealed interface OriginationTransitionResult {
    data class Applied(val newState: OriginationState) : OriginationTransitionResult

    data class Rejected(val reason: String) : OriginationTransitionResult
}

/**
 * Pure transition engine for loan origination (ADR-0211 D1/D3). Deterministic: the
 * outcome depends only on the policy and the transition. Persistence, Temporal wiring
 * and audit emission are the service's concern; this machine only decides whether a
 * commanded transition is lawful.
 *
 * Guards enforced here (before policy-level [OriginationGuard] hooks): non-blank actor
 * and reason, no transitions out of terminal states, and system actors never taking
 * human-only edges (four-eyes approval/decline, signature).
 */
class OriginationStateMachine(
    private val policy: OriginationTransitionPolicy = OriginationTransitionPolicy.standard(),
) {
    fun guard(transition: OriginationTransition): OriginationTransitionResult {
        if (transition.from.isTerminal) {
            return OriginationTransitionResult.Rejected(
                "State ${transition.from} is terminal — no outgoing transitions",
            )
        }
        if (!policy.isAllowed(transition.from, transition.to)) {
            return OriginationTransitionResult.Rejected(
                "Transition from ${transition.from} to ${transition.to} is not allowed",
            )
        }
        if (transition.actor.isBlank()) {
            return OriginationTransitionResult.Rejected("Actor must not be blank")
        }
        if (transition.reason.isBlank()) {
            return OriginationTransitionResult.Rejected("Reason must not be blank")
        }
        if (transition.actorKind == OriginationActorKind.SYSTEM && transition.to in HUMAN_ONLY_TARGETS) {
            return OriginationTransitionResult.Rejected(
                "Transition to ${transition.to} requires a human actor (four-eyes / signature)",
            )
        }
        val failure = policy.guardsFor(OriginationTransitionKey(transition.from, transition.to))
            .asSequence()
            .mapNotNull { it.evaluate(transition) }
            .firstOrNull()
        return if (failure == null) {
            OriginationTransitionResult.Applied(transition.to)
        } else {
            OriginationTransitionResult.Rejected(failure.reason)
        }
    }

    fun apply(transition: OriginationTransition): OriginationTransitionResult = guard(transition)

    companion object {
        private val HUMAN_ONLY_TARGETS: Set<OriginationState> = setOf(
            OriginationState.OFFERED,
            OriginationState.SIGNED,
        )
    }
}
