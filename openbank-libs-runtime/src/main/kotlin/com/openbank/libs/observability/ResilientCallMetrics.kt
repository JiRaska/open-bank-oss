// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException

/**
 * Separates *the breaker is open* from *the call failed* on a resilient inter-service adapter.
 *
 * #3267's third ask. Without it the two are indistinguishable outside the logs, and they mean
 * opposite things operationally: `call_failed` is the dependency misbehaving and is what you page
 * on; `breaker_open` is this service protecting itself and is a CONSEQUENCE of an earlier burst of
 * `call_failed`. A dashboard that sums them reports a long flat plateau of "sanctions failing"
 * while the dependency may already have recovered — the breaker is simply still open.
 *
 * Same CDI shape as [LlmCallMetrics] and DomainMetrics, deliberately: `@ApplicationScoped` with an
 * `Instance<MeterRegistry>` rather than a direct injection, so this bean loads harmlessly in a
 * service with no `quarkus-micrometer-*` on its classpath and every method becomes a silent no-op.
 * A plain bean, **not** a `@Produces` — a producer would drag Micrometer into the Arc type closure
 * of every consumer of openbank-libs-runtime, including the ones that make no resilient call.
 *
 * One series, tagged only with closed low-cardinality sets:
 * `openbank.resilient.call.failures{adapter,outcome}` where `outcome` is exactly `breaker_open`
 * or `call_failed`. The adapter name is a compile-time constant per call site, never a URL or an
 * id — an unbounded tag here would be a cardinality incident on the money path.
 */
@ApplicationScoped
class ResilientCallMetrics {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private fun reg(): MeterRegistry? = if (registryInstance.isResolvable) registryInstance.get() else null

    /**
     * Count one failed attempt of [adapter], classifying [error] by whether the breaker rejected
     * it or the call itself failed.
     *
     * Called per ATTEMPT, from inside the `@CircuitBreaker`-annotated method, so the counts line up
     * with what fault tolerance actually saw rather than with the single exception that escaped.
     */
    fun recordFailure(adapter: String, error: Throwable) {
        val registry = reg() ?: return
        registry.counter(
            "openbank.resilient.call.failures",
            "adapter",
            adapter,
            "outcome",
            outcomeOf(error),
        ).increment()
    }

    private fun outcomeOf(error: Throwable): String =
        if (error is CircuitBreakerOpenException) OUTCOME_BREAKER_OPEN else OUTCOME_CALL_FAILED

    companion object {
        const val OUTCOME_BREAKER_OPEN = "breaker_open"
        const val OUTCOME_CALL_FAILED = "call_failed"
    }
}
