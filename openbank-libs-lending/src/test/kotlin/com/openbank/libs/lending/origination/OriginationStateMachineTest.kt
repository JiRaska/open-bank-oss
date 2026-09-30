// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.origination

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** Covers the canonical origination graph, guards and the ADR-0211 D7 legacy mapping. */
class OriginationStateMachineTest {

    private val machine = OriginationStateMachine()
    private val now: Instant = Instant.parse("2026-07-29T12:00:00Z")

    private fun transition(
        from: OriginationState,
        to: OriginationState,
        actor: String = "officer-1",
        actorKind: OriginationActorKind = OriginationActorKind.HUMAN,
        reason: String = "valid business reason",
    ) = OriginationTransition(
        applicationId = "app-1",
        from = from,
        to = to,
        actor = actor,
        actorKind = actorKind,
        reason = reason,
        occurredAt = now,
        packVersion = "CZ-2026.1",
    )

    @Test
    fun `happy path walks the full canonical graph to DISBURSED`() {
        val path = listOf(
            OriginationState.DRAFT to OriginationState.SUBMITTED,
            OriginationState.SUBMITTED to OriginationState.KYC_PENDING,
            OriginationState.KYC_PENDING to OriginationState.DOCS_REQUIRED,
            OriginationState.DOCS_REQUIRED to OriginationState.ASSESSMENT,
            OriginationState.ASSESSMENT to OriginationState.DECISION_PENDING,
            OriginationState.DECISION_PENDING to OriginationState.FOUR_EYES,
            OriginationState.FOUR_EYES to OriginationState.OFFERED,
            OriginationState.OFFERED to OriginationState.AWAITING_SIGNATURE,
            OriginationState.AWAITING_SIGNATURE to OriginationState.SIGNED,
            OriginationState.SIGNED to OriginationState.READY_TO_DISBURSE,
            OriginationState.READY_TO_DISBURSE to OriginationState.DISBURSED,
        )
        path.forEach { (from, to) ->
            assertThat(machine.apply(transition(from, to)))
                .isEqualTo(OriginationTransitionResult.Applied(to))
        }
    }

    @Test
    fun `reflection period is a reachable but skippable edge`() {
        assertThat(machine.apply(transition(OriginationState.SIGNED, OriginationState.REFLECTION_PERIOD)))
            .isEqualTo(OriginationTransitionResult.Applied(OriginationState.REFLECTION_PERIOD))
        assertThat(machine.apply(transition(OriginationState.REFLECTION_PERIOD, OriginationState.READY_TO_DISBURSE)))
            .isEqualTo(OriginationTransitionResult.Applied(OriginationState.READY_TO_DISBURSE))
        assertThat(machine.apply(transition(OriginationState.SIGNED, OriginationState.READY_TO_DISBURSE)))
            .isEqualTo(OriginationTransitionResult.Applied(OriginationState.READY_TO_DISBURSE))
    }

    @Test
    fun `forbidden shortcut DRAFT to DISBURSED is rejected`() {
        val result = machine.apply(transition(OriginationState.DRAFT, OriginationState.DISBURSED))
        assertThat(result).isInstanceOf(OriginationTransitionResult.Rejected::class.java)
        assertThat((result as OriginationTransitionResult.Rejected).reason).contains("not allowed")
    }

    @Test
    fun `terminal states have no outgoing transitions`() {
        OriginationState.TERMINAL.forEach { terminal ->
            val result = machine.apply(transition(terminal, OriginationState.SUBMITTED))
            assertThat(result).isInstanceOf(OriginationTransitionResult.Rejected::class.java)
            assertThat((result as OriginationTransitionResult.Rejected).reason).contains("terminal")
        }
    }

    @Test
    fun `customer exit and time-driven exits are reachable from pre-disbursement states`() {
        assertThat(machine.apply(transition(OriginationState.ASSESSMENT, OriginationState.WITHDRAWN)))
            .isEqualTo(OriginationTransitionResult.Applied(OriginationState.WITHDRAWN))
        assertThat(machine.apply(transition(OriginationState.OFFERED, OriginationState.EXPIRED)))
            .isEqualTo(OriginationTransitionResult.Applied(OriginationState.EXPIRED))
    }

    @Test
    fun `system actor cannot take human-only edges (offer, signature)`() {
        val offered = machine.apply(
            transition(
                OriginationState.FOUR_EYES,
                OriginationState.OFFERED,
                actorKind = OriginationActorKind.SYSTEM,
                actor = "temporal-worker",
            ),
        )
        assertThat(offered).isInstanceOf(OriginationTransitionResult.Rejected::class.java)

        val signed = machine.apply(
            transition(
                OriginationState.AWAITING_SIGNATURE,
                OriginationState.SIGNED,
                actorKind = OriginationActorKind.SYSTEM,
                actor = "temporal-worker",
            ),
        )
        assertThat(signed).isInstanceOf(OriginationTransitionResult.Rejected::class.java)
    }

    @Test
    fun `system actor can take machine edges (timer expiry)`() {
        val result = machine.apply(
            transition(
                OriginationState.OFFERED,
                OriginationState.EXPIRED,
                actorKind = OriginationActorKind.SYSTEM,
                actor = "temporal-worker",
            ),
        )
        assertThat(result).isEqualTo(OriginationTransitionResult.Applied(OriginationState.EXPIRED))
    }

    @Test
    fun `blank actor or reason is rejected`() {
        assertThat(machine.apply(transition(OriginationState.DRAFT, OriginationState.SUBMITTED, actor = " ")))
            .isInstanceOf(OriginationTransitionResult.Rejected::class.java)
        assertThat(machine.apply(transition(OriginationState.DRAFT, OriginationState.SUBMITTED, reason = "")))
            .isInstanceOf(OriginationTransitionResult.Rejected::class.java)
    }

    @Test
    fun `policy guard hook rejects maker-equals-checker`() {
        val makerCheckerGuard = OriginationGuard { t ->
            if (t.to == OriginationState.OFFERED && t.metadata["maker"] == t.actor) {
                OriginationGuardFailure("maker must differ from checker")
            } else {
                null
            }
        }
        val guarded = OriginationStateMachine(
            OriginationTransitionPolicy(
                allowedTransitions = OriginationTransitionPolicy.standard().allowedTransitions,
                guards = mapOf(
                    OriginationTransitionKey(OriginationState.FOUR_EYES, OriginationState.OFFERED) to
                        listOf(makerCheckerGuard),
                ),
            ),
        )
        val selfApproval = transition(OriginationState.FOUR_EYES, OriginationState.OFFERED, actor = "officer-1")
            .copy(metadata = mapOf("maker" to "officer-1"))
        assertThat(guarded.apply(selfApproval))
            .isEqualTo(OriginationTransitionResult.Rejected("maker must differ from checker"))

        val fourEyes = transition(OriginationState.FOUR_EYES, OriginationState.OFFERED, actor = "officer-2")
            .copy(metadata = mapOf("maker" to "officer-1"))
        assertThat(guarded.apply(fourEyes))
            .isEqualTo(OriginationTransitionResult.Applied(OriginationState.OFFERED))
    }

    @Test
    fun `legacy v0_11_5 statuses map onto the canonical graph (ADR-0211 D7)`() {
        assertThat(LegacyOriginationMigration.mapLegacyStatus("PROPOSED", wasSubmitted = false))
            .isEqualTo(OriginationState.DRAFT)
        assertThat(LegacyOriginationMigration.mapLegacyStatus("PROPOSED", wasSubmitted = true))
            .isEqualTo(OriginationState.SUBMITTED)
        assertThat(LegacyOriginationMigration.mapLegacyStatus("APPROVED", wasSubmitted = true))
            .isEqualTo(OriginationState.OFFERED)
        assertThat(LegacyOriginationMigration.mapLegacyStatus("REJECTED", wasSubmitted = true))
            .isEqualTo(OriginationState.DECLINED)
        assertThat(LegacyOriginationMigration.mapLegacyStatus("DISBURSED", wasSubmitted = true))
            .isEqualTo(OriginationState.DISBURSED)
        assertThat(LegacyOriginationMigration.mapLegacyStatus("CORRUPT", wasSubmitted = true)).isNull()
    }

    @Test
    fun `every non-terminal state can reach a terminal state`() {
        OriginationState.entries.filter { !it.isTerminal }.forEach { state ->
            val reachable = reachableFrom(state)
            assertThat(reachable.intersect(OriginationState.TERMINAL))
                .`as`("state %s must reach a terminal state", state)
                .isNotEmpty()
        }
    }

    private fun reachableFrom(start: OriginationState): Set<OriginationState> {
        val policy = OriginationTransitionPolicy.standard()
        val seen = mutableSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            policy.allowedTargets(current).forEach { next ->
                if (seen.add(next)) queue.addLast(next)
            }
        }
        return seen
    }
}
