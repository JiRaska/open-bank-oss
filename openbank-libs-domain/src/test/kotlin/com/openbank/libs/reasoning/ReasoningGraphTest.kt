// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.reasoning

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The graph's whole job is to be unambiguous about HOW a run ended and WHICH path it took, so that
 * is what is asserted. The three outcomes are deliberately distinct values and these tests keep them
 * distinguishable: an agent that "stopped after 5 steps" and one that routed somewhere that does not
 * exist look identical in a log and need opposite fixes.
 */
class ReasoningGraphTest {

    private data class S(val calls: List<String> = emptyList(), val done: Boolean = false)

    private fun mark(id: String): (S) -> S = { it.copy(calls = it.calls + id) }

    @Test
    fun `a linear graph runs every node once, in order`() {
        val g = ReasoningGraph.builder<S>()
            .node("a", mark("a"), goto = "b")
            .node("b", mark("b"), goto = "c")
            .node("c", mark("c"), goto = ReasoningGraph.FINISH)
            .build()

        val run = g.run(S())

        assertThat(run.outcome).isEqualTo(ReasoningGraph.Outcome.COMPLETED)
        assertThat(run.path).containsExactly("a", "b", "c")
        assertThat(run.state.calls).containsExactly("a", "b", "c")
    }

    @Test
    fun `a conditional route decides on the state the node just produced`() {
        val g = ReasoningGraph.builder<S>()
            .node("model", { it.copy(calls = it.calls + "model", done = it.calls.size >= 2) }) { s ->
                if (s.done) ReasoningGraph.FINISH else "tools"
            }
            .node("tools", mark("tools"), goto = "model")
            .build()

        val run = g.run(S())

        // model(1 call) -> tools(2) -> model sees 2 already recorded, sets done, finishes.
        // A repeated id is a legitimate loop, not a defect.
        assertThat(run.outcome).isEqualTo(ReasoningGraph.Outcome.COMPLETED)
        assertThat(run.path).containsExactly("model", "tools", "model")
    }

    @Test
    fun `the step cap stops an otherwise endless loop and says so`() {
        val g = ReasoningGraph.builder<S>()
            .node("a", mark("a"), goto = "b")
            .node("b", mark("b"), goto = "a")
            .maxSteps(4)
            .build()

        val run = g.run(S())

        assertThat(run.outcome).isEqualTo(ReasoningGraph.Outcome.MAX_STEPS)
        assertThat(run.path).hasSize(4)
        // The state is the last one produced, not a discarded failure state: the caller needs the
        // partial work in order to answer honestly rather than with a bare "stopped".
        assertThat(run.state.calls).containsExactly("a", "b", "a", "b")
    }

    @Test
    fun `a conditional route to an unknown node is NO_ROUTE, not a silent stop`() {
        val g = ReasoningGraph.builder<S>()
            .node("a", mark("a")) { "typo" }
            .node("b", mark("b"), goto = ReasoningGraph.FINISH)
            .build()

        val run = g.run(S())

        assertThat(run.outcome).isEqualTo(ReasoningGraph.Outcome.NO_ROUTE)
        assertThat(run.path).containsExactly("a")
    }

    @Test
    fun `an unconditional edge to a node that does not exist fails at build time`() {
        assertThatThrownBy {
            ReasoningGraph.builder<S>()
                .node("a", mark("a"), goto = "nope")
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("nope")
    }

    @Test
    fun `an unreachable node fails at build time when every route is unconditional`() {
        assertThatThrownBy {
            ReasoningGraph.builder<S>()
                .node("a", mark("a"), goto = ReasoningGraph.FINISH)
                .node("orphan", mark("orphan"), goto = ReasoningGraph.FINISH)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("orphan")
    }

    @Test
    fun `a conditional-only successor is NOT rejected as unreachable`() {
        // The stated blind spot, asserted so it stays deliberate: a target named only inside a
        // lambda cannot be seen, and rejecting this correct graph would be worse than the gap.
        val g = ReasoningGraph.builder<S>()
            .node("a", mark("a")) { "b" }
            .node("b", mark("b"), goto = ReasoningGraph.FINISH)
            .build()

        assertThat(g.run(S()).path).containsExactly("a", "b")
    }

    @Test
    fun `duplicate node ids are rejected`() {
        assertThatThrownBy {
            ReasoningGraph.builder<S>()
                .node("a", mark("a"), goto = ReasoningGraph.FINISH)
                .node("a", mark("a"), goto = ReasoningGraph.FINISH)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("duplicate")
    }

    @Test
    fun `maxSteps must be positive`() {
        assertThatThrownBy { ReasoningGraph.builder<S>().maxSteps(0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the entry node defaults to the first declared and can be overridden`() {
        val g = ReasoningGraph.builder<S>()
            .node("first", mark("first")) { ReasoningGraph.FINISH }
            .node("second", mark("second")) { ReasoningGraph.FINISH }
            .entry("second")
            .build()

        assertThat(g.run(S()).path).containsExactly("second")
    }

    @Test
    fun `state is threaded, never shared`() {
        val g = ReasoningGraph.builder<S>()
            .node("a", { it.copy(calls = listOf("only-a")) }, goto = "b")
            .node("b", { s ->
                s.copy(calls = s.calls + listOf("saw:" + s.calls.joinToString()))
            }, goto = ReasoningGraph.FINISH)
            .build()

        assertThat(g.run(S(calls = listOf("initial"))).state.calls)
            .containsExactly("only-a", "saw:only-a")
    }

    @Test
    fun `running the same graph twice does not carry state between runs`() {
        val g = ReasoningGraph.builder<S>()
            .node("a", mark("a"), goto = ReasoningGraph.FINISH)
            .build()

        assertThat(g.run(S()).state.calls).containsExactly("a")
        assertThat(g.run(S()).state.calls).containsExactly("a")
    }
}
