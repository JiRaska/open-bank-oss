// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.origination

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Structural invariants of the canonical origination graph (ADR-0211 D1). A terminal state that
 * quietly gained an outgoing edge, or a live state that lost its abandon/expire exit, is the kind
 * of change that reviews cleanly and strands applications.
 */
class OriginationTransitionPolicyTest {

    private val policy = OriginationTransitionPolicy.standard()

    @Test
    fun `terminal states have no outgoing transitions`() {
        OriginationState.TERMINAL.forEach { s ->
            assertThat(s.isTerminal).isTrue()
            assertThat(policy.allowedTargets(s)).describedAs("targets of %s", s).isEmpty()
            assertThat(policy.allowedTransitions).doesNotContainKey(s)
        }
    }

    @Test
    fun `every non-terminal state has at least one outgoing transition`() {
        OriginationState.entries.filterNot { it.isTerminal }.forEach { s ->
            assertThat(policy.allowedTargets(s)).describedAs("targets of %s", s).isNotEmpty()
        }
    }

    @Test
    fun `no state may transition to itself`() {
        OriginationState.entries.forEach { s ->
            assertThat(policy.isAllowed(s, s)).describedAs("self-transition %s", s).isFalse()
        }
    }

    @Test
    fun `DISBURSED is reachable only from READY_TO_DISBURSE`() {
        val sourcesOfDisbursed = policy.allowedTransitions
            .filterValues { OriginationState.DISBURSED in it }
            .keys
        assertThat(sourcesOfDisbursed).containsExactly(OriginationState.READY_TO_DISBURSE)
    }

    @Test
    fun `a signed application can no longer be withdrawn or declined - only expire`() {
        listOf(OriginationState.SIGNED, OriginationState.REFLECTION_PERIOD, OriginationState.READY_TO_DISBURSE)
            .forEach { s ->
                assertThat(policy.isAllowed(s, OriginationState.WITHDRAWN)).describedAs("%s -> WITHDRAWN", s).isFalse()
                assertThat(policy.isAllowed(s, OriginationState.DECLINED)).describedAs("%s -> DECLINED", s).isFalse()
                assertThat(policy.isAllowed(s, OriginationState.EXPIRED)).describedAs("%s -> EXPIRED", s).isTrue()
            }
    }

    @Test
    fun `a draft that was never submitted can be withdrawn but not declined or expired`() {
        assertThat(policy.allowedTargets(OriginationState.DRAFT))
            .containsExactlyInAnyOrder(OriginationState.SUBMITTED, OriginationState.WITHDRAWN)
    }

    @Test
    fun `an offer only becomes binding through the signature states`() {
        assertThat(policy.isAllowed(OriginationState.OFFERED, OriginationState.SIGNED)).isFalse()
        assertThat(policy.isAllowed(OriginationState.OFFERED, OriginationState.AWAITING_SIGNATURE)).isTrue()
        assertThat(policy.isAllowed(OriginationState.AWAITING_SIGNATURE, OriginationState.SIGNED)).isTrue()
    }

    @Test
    fun `four-eyes cannot be bypassed on the way from a decision to an offer`() {
        assertThat(policy.isAllowed(OriginationState.DECISION_PENDING, OriginationState.OFFERED)).isFalse()
        assertThat(policy.isAllowed(OriginationState.DECISION_PENDING, OriginationState.FOUR_EYES)).isTrue()
        assertThat(policy.isAllowed(OriginationState.FOUR_EYES, OriginationState.OFFERED)).isTrue()
    }

    @Test
    fun `every declared target is a known state and an unknown source yields an empty set`() {
        val empty = OriginationTransitionPolicy(allowedTransitions = emptyMap())
        assertThat(empty.allowedTargets(OriginationState.DRAFT)).isEmpty()
        assertThat(empty.isAllowed(OriginationState.DRAFT, OriginationState.SUBMITTED)).isFalse()
    }

    @Test
    fun `guards are returned only for their exact transition key`() {
        val guard = OriginationGuard { OriginationGuardFailure("maker equals checker") }
        val key = OriginationTransitionKey(OriginationState.DECISION_PENDING, OriginationState.FOUR_EYES)
        val guarded = OriginationTransitionPolicy(policy.allowedTransitions, mapOf(key to listOf(guard)))
        assertThat(guarded.guardsFor(key)).containsExactly(guard)
        assertThat(
            guarded.guardsFor(OriginationTransitionKey(OriginationState.FOUR_EYES, OriginationState.OFFERED)),
        ).isEmpty()
    }
}
