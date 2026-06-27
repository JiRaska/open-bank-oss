// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.saga

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SagaStateMachineTest {

    private enum class S { STARTED, RUNNING, COMPLETED, COMPENSATING, COMPENSATED, FAILED }

    private val policy = SagaTransitionPolicy(
        mapOf(
            S.STARTED to setOf(S.RUNNING, S.COMPENSATING, S.FAILED),
            S.RUNNING to setOf(S.COMPLETED, S.COMPENSATING, S.FAILED),
            S.COMPENSATING to setOf(S.COMPENSATED, S.FAILED),
            S.COMPLETED to emptySet(),
            S.COMPENSATED to emptySet(),
            S.FAILED to emptySet(),
        ),
    )
    private val machine = SagaStateMachine(policy)

    @Test
    fun `allows declared transitions`() {
        assertThat(machine.isValid(S.STARTED, S.RUNNING)).isTrue()
        assertThat(machine.isValid(S.RUNNING, S.COMPLETED)).isTrue()
        assertThat(machine.isValid(S.RUNNING, S.COMPENSATING)).isTrue()
    }

    @Test
    fun `rejects undeclared transitions`() {
        assertThat(machine.isValid(S.STARTED, S.COMPLETED)).isFalse()
        assertThat(machine.isValid(S.COMPLETED, S.RUNNING)).isFalse()
    }

    @Test
    fun `terminal states have no outgoing transitions`() {
        assertThat(machine.isTerminal(S.COMPLETED)).isTrue()
        assertThat(machine.isTerminal(S.COMPENSATED)).isTrue()
        assertThat(machine.isTerminal(S.FAILED)).isTrue()
        assertThat(machine.isTerminal(S.STARTED)).isFalse()
        assertThat(machine.isTerminal(S.RUNNING)).isFalse()
    }

    @Test
    fun `unknown source state is treated as terminal and disallows transitions`() {
        val empty = SagaStateMachine(SagaTransitionPolicy<S>(emptyMap()))
        assertThat(empty.isTerminal(S.STARTED)).isTrue()
        assertThat(empty.isValid(S.STARTED, S.RUNNING)).isFalse()
    }

    @Test
    fun `transition returns Applied for valid and Rejected for invalid`() {
        assertThat(machine.transition(S.STARTED, S.RUNNING))
            .isEqualTo(SagaTransitionResult.Applied(S.RUNNING))

        val rejected = machine.transition(S.COMPLETED, S.RUNNING)
        assertThat(rejected).isInstanceOf(SagaTransitionResult.Rejected::class.java)
        assertThat((rejected as SagaTransitionResult.Rejected).reason)
            .contains("COMPLETED", "RUNNING", "not allowed")
    }

    @Test
    fun `requireValid returns target for valid transition`() {
        assertThat(machine.requireValid(S.STARTED, S.RUNNING)).isEqualTo(S.RUNNING)
    }

    @Test
    fun `requireValid throws with custom message for invalid transition`() {
        assertThatThrownBy { machine.requireValid(S.COMPLETED, S.RUNNING) { f, t -> "bad $f -> $t" } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("bad COMPLETED -> RUNNING")
    }
}
