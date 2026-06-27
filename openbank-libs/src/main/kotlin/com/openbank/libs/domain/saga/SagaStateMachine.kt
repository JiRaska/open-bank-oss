// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.saga

/**
 * Explicit, deterministic transition policy for a saga state machine.
 *
 * Parameterised over the saga's own state type [S] (typically an enum) so each saga keeps
 * its domain-meaningful states while sharing the validation primitive. A state whose entry
 * in [allowedTransitions] is empty (or absent) is **terminal**.
 *
 * This mirrors `CaseTransitionPolicy` in `libs/domain/case`; sagas and cases are both
 * deterministic state machines and share the house pattern. See ADR-0045.
 */
data class SagaTransitionPolicy<S>(val allowedTransitions: Map<S, Set<S>>) {
    fun isAllowed(from: S, to: S): Boolean = allowedTransitions[from].orEmpty().contains(to)

    fun isTerminal(state: S): Boolean = allowedTransitions[state].orEmpty().isEmpty()
}

/** Outcome of a requested saga transition. */
sealed interface SagaTransitionResult<S> {
    data class Applied<S>(val newState: S) : SagaTransitionResult<S>
    data class Rejected<S>(val reason: String) : SagaTransitionResult<S>
}

/**
 * Pure saga transition engine: the outcome depends only on the policy and the requested
 * transition. Holds no state of its own, so a single instance is safe to share.
 */
class SagaStateMachine<S>(private val policy: SagaTransitionPolicy<S>) {
    fun isValid(from: S, to: S): Boolean = policy.isAllowed(from, to)

    fun isTerminal(state: S): Boolean = policy.isTerminal(state)

    fun transition(from: S, to: S): SagaTransitionResult<S> = if (policy.isAllowed(from, to)) {
        SagaTransitionResult.Applied(to)
    } else {
        SagaTransitionResult.Rejected("Transition from $from to $to is not allowed")
    }

    /**
     * Validates the transition and returns [to], throwing [IllegalArgumentException] when it
     * is not allowed. Preserves the `require(...)`-style contract used by domain aggregates;
     * [describe] customises the failure message (e.g. to include an aggregate id).
     */
    fun requireValid(from: S, to: S, describe: (S, S) -> String = { f, t -> "Invalid saga transition: $f → $t" }): S {
        require(policy.isAllowed(from, to)) { describe(from, to) }
        return to
    }
}
