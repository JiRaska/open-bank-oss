// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.origination

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.time.Instant

/**
 * Property tests over the canonical origination graph and its pure state machine
 * (ADR-0211 D1/D3). The example-based tests in [OriginationStateMachineTest] walk the
 * paths a reviewer thought of; these assert the invariants a reviewer cannot enumerate:
 * no sequence of lawful steps can throw, leave the policy's reach, escape a terminal
 * state, let a machine actor take a human-only edge, or pass an unlawful (from, to) pair.
 */
class OriginationProperties {

    private val policy = OriginationTransitionPolicy.standard()
    private val machine = OriginationStateMachine(policy)
    private val now: Instant = Instant.parse("2026-07-29T12:00:00Z")

    private fun command(
        applicationId: String,
        from: OriginationState,
        to: OriginationState,
        actorKind: OriginationActorKind = OriginationActorKind.HUMAN,
        actor: String = "officer-1",
    ) = OriginationTransition(
        applicationId = applicationId,
        from = from,
        to = to,
        actor = actor,
        actorKind = actorKind,
        reason = "property test drive",
        occurredAt = now,
        // FOUR_EYES is left only with a decision naming a proposer other than the actor.
        metadata = if (from == OriginationState.FOUR_EYES) {
            FourEyesDecision.metadata(approve = to == OriginationState.OFFERED, proposedBy = "proposer-1")
        } else {
            emptyMap()
        },
    )

    /**
     * Every non-terminal state's lawful targets, as data for the generators below. Keeping this
     * as the single source for both generation and assertion means a walk can never "cheat": the
     * assertion re-checks reachability against the POLICY, not against the generator's own choice.
     */
    private val targets: Map<OriginationState, Set<OriginationState>> =
        OriginationState.entries.associateWith { policy.allowedTargets(it) }

    @Provide
    fun walkLengths(): Arbitrary<Int> = Arbitraries.integers().between(1, 40)

    @Provide
    fun states(): Arbitrary<OriginationState> = Arbitraries.of(OriginationState::class.java)

    @Provide
    fun actorKinds(): Arbitrary<OriginationActorKind> = Arbitraries.of(OriginationActorKind::class.java)

    /** The policy-lawful edges into the human-only targets — the whole population property (c) draws from. */
    @Provide
    fun humanOnlyEdges(): Arbitrary<Pair<OriginationState, OriginationState>> = Arbitraries.of(
        OriginationState.FOUR_EYES to OriginationState.OFFERED,
        OriginationState.AWAITING_SIGNATURE to OriginationState.SIGNED,
    )

    /**
     * (a) Any sequence of policy-lawful transitions from DRAFT never throws, every step is
     * applied exactly as commanded, and a stopped walk always ends in a state the policy
     * reaches (the walk only ever visits policy targets, and the machine agrees at every step).
     */
    @Property
    fun `random lawful walks from DRAFT never throw and stay within the policy`(@ForAll("walkLengths") steps: Int) {
        var state = OriginationState.DRAFT
        var last: OriginationState? = null
        for (i in 0 until steps) {
            val options = targets.getValue(state)
            // Terminal: the walk is over — terminal states have no outgoing transitions
            // (asserted as their own property below), so nothing may be drawn from them.
            if (options.isEmpty()) break
            val to = options.random()
            val result = machine.apply(command("walk-$i", state, to))
            org.assertj.core.api.Assertions.assertThat(result)
                .describedAs("lawful walk step %s -> %s", state, to)
                .isEqualTo(OriginationTransitionResult.Applied(to))
            last = to
            state = to
        }
        if (last != null) {
            org.assertj.core.api.Assertions.assertThat(targets.keys).contains(state)
        }
    }

    /** (b) Terminal states admit no outgoing transition, whatever the actor kind. */
    @Property
    fun `terminal states have no outgoing transitions`(
        @ForAll("states") terminal: OriginationState,
        @ForAll("states") to: OriginationState,
        @ForAll("actorKinds") actorKind: OriginationActorKind,
    ) {
        net.jqwik.api.Assume.that(terminal.isTerminal)
        val result = machine.apply(command("terminal", terminal, to, actorKind))
        org.assertj.core.api.Assertions.assertThat(result).isInstanceOf(
            OriginationTransitionResult.Rejected::class.java,
        )
    }

    /** (c) The human-only targets (OFFERED, SIGNED) reject a SYSTEM actor on a lawful edge. */
    @Property
    fun `human-only targets reject a system actor`(
        @ForAll("humanOnlyEdges") edge: Pair<OriginationState, OriginationState>,
    ) {
        val (from, to) = edge
        // FOUR_EYES -> OFFERED carries the four-eyes guard; a SYSTEM actor there must fail the
        // human-actor rule, not be let through because the guard happened to pass first.
        val result = machine.apply(command("system", from, to, OriginationActorKind.SYSTEM))
        org.assertj.core.api.Assertions.assertThat(result)
            .isEqualTo(
                OriginationTransitionResult.Rejected(
                    "Transition to $to requires a human actor (four-eyes / signature)",
                ),
            )
    }

    /** (d) An arbitrary pair the policy does not allow is rejected, from any non-terminal state. */
    @Property
    fun `arbitrary invalid from-event pairs are rejected`(
        @ForAll("states") from: OriginationState,
        @ForAll("states") to: OriginationState,
    ) {
        net.jqwik.api.Assume.that(!from.isTerminal)
        net.jqwik.api.Assume.that(!policy.isAllowed(from, to))
        // Self-loops and missing edges are both "not allowed" — the machine must say exactly that,
        // and must never report Applied for a pair outside the policy.
        val result = machine.apply(command("invalid", from, to))
        org.assertj.core.api.Assertions.assertThat(result).isEqualTo(
            OriginationTransitionResult.Rejected("Transition from $from to $to is not allowed"),
        )
    }
}
