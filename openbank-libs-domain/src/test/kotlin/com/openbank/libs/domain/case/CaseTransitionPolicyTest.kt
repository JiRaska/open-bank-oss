// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.case

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The case graph is data, so the interesting assertions are structural invariants of
 * [CaseTransitionPolicy.standard] — a transition quietly added or dropped from the map is
 * otherwise invisible until a service 409s in production.
 */
class CaseTransitionPolicyTest {

    private val policy = CaseTransitionPolicy.standard()

    private fun transition(from: CaseStatus, to: CaseStatus) = CaseTransition(
        caseId = CaseId.new(),
        caseType = CaseType.GENERIC_REVIEW,
        fromStatus = from,
        toStatus = to,
        reasonCode = CaseReasonCode.MANUAL_UPDATE,
        actor = "operator-1",
        occurredAt = Instant.now(),
    )

    @Test
    fun `a declared edge is allowed and its reverse is not automatically allowed`() {
        assertThat(policy.isAllowed(CaseStatus.DRAFT, CaseStatus.OPEN)).isTrue()
        assertThat(policy.isAllowed(CaseStatus.OPEN, CaseStatus.DRAFT)).isFalse()
    }

    @Test
    fun `an undeclared source status allows nothing rather than throwing`() {
        val empty = CaseTransitionPolicy(allowedTransitions = emptyMap())
        assertThat(empty.isAllowed(CaseStatus.OPEN, CaseStatus.CLOSED)).isFalse()
        assertThat(empty.guardsFor(transition(CaseStatus.OPEN, CaseStatus.CLOSED))).isEmpty()
    }

    @Test
    fun `no status may transition to itself`() {
        CaseStatus.entries.forEach { s ->
            assertThat(policy.isAllowed(s, s)).describedAs("self-transition %s", s).isFalse()
        }
    }

    @Test
    fun `every status is a declared source and every status is reachable as a target`() {
        val sources = policy.allowedTransitions.keys
        val targets = policy.allowedTransitions.values.flatten().toSet()
        assertThat(sources).containsExactlyInAnyOrderElementsOf(CaseStatus.entries)
        // DRAFT is the entry point and so is never a target; everything else must be reachable.
        assertThat(targets).containsExactlyInAnyOrderElementsOf(CaseStatus.entries - CaseStatus.DRAFT)
    }

    @Test
    fun `a decided case can only be closed or reopened, never flipped to the other decision`() {
        assertThat(policy.allowedTransitions[CaseStatus.APPROVED])
            .containsExactlyInAnyOrder(CaseStatus.CLOSED, CaseStatus.OPEN)
        assertThat(policy.isAllowed(CaseStatus.APPROVED, CaseStatus.REJECTED)).isFalse()
        assertThat(policy.isAllowed(CaseStatus.REJECTED, CaseStatus.APPROVED)).isFalse()
    }

    @Test
    fun `a closed or cancelled case can only be reopened`() {
        assertThat(policy.allowedTransitions[CaseStatus.CLOSED]).containsExactly(CaseStatus.OPEN)
        assertThat(policy.allowedTransitions[CaseStatus.CANCELLED]).containsExactly(CaseStatus.OPEN)
    }

    @Test
    fun `waiting states cannot jump straight to a decision`() {
        listOf(CaseStatus.WAITING_FOR_CUSTOMER, CaseStatus.WAITING_FOR_EXTERNAL_PARTY).forEach { s ->
            assertThat(policy.isAllowed(s, CaseStatus.APPROVED)).describedAs("%s -> APPROVED", s).isFalse()
            assertThat(policy.isAllowed(s, CaseStatus.REJECTED)).describedAs("%s -> REJECTED", s).isFalse()
        }
    }

    @Test
    fun `guards are keyed by the exact from-to pair and returned only for it`() {
        val guard = CaseTransitionGuard { CaseGuardFailure("blocked: ${it.actor}") }
        val guarded = CaseTransitionPolicy(
            allowedTransitions = policy.allowedTransitions,
            guards = mapOf(CaseTransitionKey(CaseStatus.OPEN, CaseStatus.APPROVED) to listOf(guard)),
        )
        val matching = transition(CaseStatus.OPEN, CaseStatus.APPROVED)
        assertThat(guarded.guardsFor(matching)).containsExactly(guard)
        assertThat(guarded.guardsFor(transition(CaseStatus.OPEN, CaseStatus.REJECTED))).isEmpty()
        assertThat(guarded.guardsFor(transition(CaseStatus.APPROVED, CaseStatus.OPEN))).isEmpty()
        assertThat(guard.evaluate(matching)).isEqualTo(CaseGuardFailure("blocked: operator-1"))
    }

    @Test
    fun `a guard returning null is a pass, not a failure`() {
        val passing = CaseTransitionGuard { null }
        assertThat(passing.evaluate(transition(CaseStatus.DRAFT, CaseStatus.OPEN))).isNull()
    }
}
