// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.reasoning

/**
 * A small, explicit state graph for agent reasoning loops (ADR-0265 slice 1) — the layer
 * `agents.yaml` declared as `langgraph` and never had.
 *
 * ## Why this and not LangGraph
 *
 * LangGraph is a Python library. This fleet is Kotlin/Quarkus and already runs Temporal, which owns
 * durable execution — the property a reasoning graph most needs and the one a library cannot
 * provide on its own. Adopting LangGraph would mean a second language runtime, its own image, CI
 * lane, dependency scanning, threat model and AGPL-boundary question, to buy a graph abstraction
 * that fits in this file. So the declaration was corrected to name what runs, and this is it.
 *
 * ## What it is
 *
 * Nodes transform state. Edges decide what runs next, from the state. A run ends when a node routes
 * to [FINISH], when no edge matches, or when the step cap is hit — and the outcome says WHICH, so
 * "the agent stopped" is never ambiguous. Every run returns the ordered list of nodes it visited,
 * which is what makes a run explainable after the fact rather than reconstructable from logs.
 *
 * ## Deliberately not here
 *
 * - **No concurrency, no scheduling, no retries.** Temporal owns those. A graph that also retried
 *   would give a workflow two competing retry policies.
 * - **Nothing non-deterministic.** No clock, no randomness, no I/O of its own. That is what lets a
 *   graph run inside a Temporal workflow at all, where a replay must reproduce the same path; every
 *   side effect belongs in a node, which in a workflow means an activity call.
 * - **No global mutable state.** [S] is threaded through and returned, so a node cannot leave a
 *   half-applied change behind on a path that was not taken.
 *
 * Construction throws [IllegalArgumentException] on a dangling unconditional edge target, and on an
 * unreachable node in a graph whose routes are ALL unconditional — a typo'd node id should fail at
 * build time rather than routing one live run to [Outcome.NO_ROUTE] months later. The limit is
 * stated rather than glossed: a conditional route's targets live inside a lambda and cannot be
 * inspected, so in a graph with any conditional edge only the runtime outcome can catch a bad
 * target. [Builder.build] documents why the check is not stretched to cover it.
 */
class ReasoningGraph<S> private constructor(
    private val entry: String,
    private val nodes: Map<String, (S) -> S>,
    private val routes: Map<String, (S) -> String>,
    private val maxSteps: Int,
) {

    /** Route target meaning "stop here, successfully". */
    enum class Outcome {
        /** A node routed to [FINISH]. */
        COMPLETED,

        /** The step cap was reached. The state is the last one produced, not a failure state. */
        MAX_STEPS,

        /** A route returned a target that is not a node and is not [FINISH]. */
        NO_ROUTE,
    }

    /**
     * @param state the state after the last node that ran.
     * @param path node ids in visit order — the run's explanation. A repeated id is a legitimate
     *   loop (model → tools → model), not a defect.
     * @param outcome why the run stopped. Never inferred from [path] size: `MAX_STEPS` and a
     *   graph that simply happened to take exactly that many steps are different facts.
     */
    data class Run<S>(val state: S, val path: List<String>, val outcome: Outcome)

    fun run(initial: S): Run<S> {
        var state = initial
        var current = entry
        val path = mutableListOf<String>()
        repeat(maxSteps) {
            val node = nodes[current] ?: return Run(state, path, Outcome.NO_ROUTE)
            path += current
            state = node(state)
            val next = routes.getValue(current)(state)
            if (next == FINISH) return Run(state, path, Outcome.COMPLETED)
            if (next !in nodes) return Run(state, path, Outcome.NO_ROUTE)
            current = next
        }
        return Run(state, path, Outcome.MAX_STEPS)
    }

    class Builder<S> internal constructor() {
        private val nodes = LinkedHashMap<String, (S) -> S>()
        private val routes = LinkedHashMap<String, (S) -> String>()
        private var entry: String? = null
        private var maxSteps = DEFAULT_MAX_STEPS
        private val unconditionalTargets = LinkedHashMap<String, String>()
        private var hasConditionalRoute = false

        /**
         * Add a node and the route it takes afterwards.
         *
         * @param transform what the node does to the state. In a Temporal workflow this is where an
         *   activity call goes; the graph itself performs no I/O.
         * @param next the id to run next, or [FINISH]. Evaluated on the state the node just
         *   produced, so a node can decide its own successor from its own result.
         */
        fun node(id: String, transform: (S) -> S, next: (S) -> String): Builder<S> = apply {
            declare(id, transform, next)
            hasConditionalRoute = true
        }

        /** Convenience for an unconditional edge — the only kind whose target is statically known. */
        fun node(id: String, transform: (S) -> S, goto: String): Builder<S> = apply {
            declare(id, transform) { goto }
            unconditionalTargets[id] = goto
        }

        private fun declare(id: String, transform: (S) -> S, next: (S) -> String) {
            require(id != FINISH) { "'$FINISH' is reserved as the terminal route target" }
            require(nodes.put(id, transform) == null) { "duplicate node id '$id'" }
            routes[id] = next
            if (entry == null) entry = id
        }

        /** The node to start from. Defaults to the first one declared. */
        fun entry(id: String): Builder<S> = apply { entry = id }

        /**
         * Hard cap on node executions. This is the thing that makes an LLM tool loop terminate, so
         * it is not optional and cannot be zero or negative.
         */
        fun maxSteps(n: Int): Builder<S> = apply {
            require(n > 0) { "maxSteps must be positive, got $n" }
            maxSteps = n
        }

        fun build(): ReasoningGraph<S> {
            val start = requireNotNull(entry) { "graph has no nodes" }
            require(start in nodes) { "entry node '$start' is not declared" }
            val unknown = unconditionalTargets.values.filter { it != FINISH && it !in nodes }
            require(unknown.isEmpty()) { "edge target(s) not declared as nodes: ${unknown.sorted()}" }
            // Reachability can only be PROVEN over unconditional edges: a conditional route's
            // targets live inside a lambda and are not inspectable. So the orphan check runs only
            // for a fully unconditional graph, where the answer is exact. Claiming more would mean
            // rejecting a legitimate conditional-only successor — a gate that fires on correct code
            // is worse than one with a stated blind spot, and at runtime an unreachable typo still
            // surfaces as Outcome.NO_ROUTE rather than as a wrong answer.
            if (!hasConditionalRoute) {
                val orphans = nodes.keys - unconditionalTargets.values.toSet() - start
                require(orphans.isEmpty()) { "unreachable node(s): ${orphans.sorted()}" }
            }
            return ReasoningGraph(start, nodes.toMap(), routes.toMap(), maxSteps)
        }
    }

    companion object {
        const val FINISH = "__finish__"

        /** Matches the fleet's existing hand-written tool loops (agent-service, copilot). */
        const val DEFAULT_MAX_STEPS = 5

        fun <S> builder(): Builder<S> = Builder()
    }
}
