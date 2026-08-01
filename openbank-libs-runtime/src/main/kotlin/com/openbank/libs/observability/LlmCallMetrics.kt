// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import com.openbank.libs.llm.LlmCallMetricsPort
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.time.Duration

/**
 * Micrometer implementation of [LlmCallMetricsPort] (ADR-0174 / ADR-0112) — the one place the
 * fleet's LLM call volume, token consumption and latency are recorded.
 *
 * **Why its own bean and not another method on [DomainMetrics].** DomainMetrics is the domain-event
 * façade and it sits exactly at detekt's `TooManyFunctions` ceiling: adding `recordCall` to it took
 * the class to 30 functions and failed the gate. That ceiling is doing its job here rather than
 * being an obstacle — LLM calls are infrastructure telemetry, not a banking domain event like
 * `paymentSubmitted` or `kycVerdict`, and the port they implement is a different contract with a
 * different set of callers. Suppressing the rule would have kept a wrong grouping.
 *
 * Same CDI shape as DomainMetrics, deliberately: `@ApplicationScoped` with an `Instance<MeterRegistry>`
 * rather than a direct injection, so this bean loads harmlessly in a service that has no
 * `quarkus-micrometer-*` on its classpath and every method becomes a silent no-op. It is a plain
 * bean, **not** a `@Produces` — a producer here would drag Micrometer into the Arc type closure of
 * every consumer of openbank-libs-runtime, including the ones that never make an LLM call.
 *
 * Three series, tagged only with closed low-cardinality sets:
 *  - `openbank.llm.requests{model,outcome}` — call volume and reliability;
 *  - `openbank.llm.tokens{model,kind}` — `prompt` and `completion` separately, because every
 *    provider prices them differently, so a combined total cannot be costed at all;
 *  - `openbank.llm.call.duration{model,outcome}` — timed on failures too, since a timeout is the
 *    slowest and most interesting case.
 *
 * **No cost metric here on purpose.** Price per token lives in the `openbank:llm_price_usd_per_token`
 * Prometheus recording rules, where a provider's rate-card change is a gitops edit that reloads in
 * place. A constant compiled into the fleet would be wrong the first time a rate changes, and
 * silently so.
 */
@ApplicationScoped
class LlmCallMetrics : LlmCallMetricsPort {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private fun reg(): MeterRegistry? = if (registryInstance.isResolvable) registryInstance.get() else null

    override fun recordCall(
        model: String,
        outcome: String,
        promptTokens: Int,
        completionTokens: Int,
        durationNanos: Long,
    ) {
        val registry = reg() ?: return
        Counter.builder("openbank.llm.requests")
            .tags("model", model, "outcome", outcome)
            .register(registry)
            .increment()
        // Skip zero increments: providers omit `usage` on an error, and a counter that only ever
        // sees 0 still creates the series — a flat line that reads as "no spend" rather than "no
        // data", which is the same misreading the business dashboards hit with pinned-at-0 counters.
        recordTokens(registry, model, "prompt", promptTokens)
        recordTokens(registry, model, "completion", completionTokens)
        Timer.builder("openbank.llm.call.duration")
            .tags("model", model, "outcome", outcome)
            .publishPercentiles(P50, P95, P99)
            .publishPercentileHistogram()
            .register(registry)
            .record(Duration.ofNanos(durationNanos))
    }

    private fun recordTokens(registry: MeterRegistry, model: String, kind: String, tokens: Int) {
        if (tokens <= 0) return
        Counter.builder("openbank.llm.tokens")
            .tags("model", model, "kind", kind)
            .register(registry)
            .increment(tokens.toDouble())
    }

    private companion object {
        // Declared as constants, not inline: detekt's MagicNumber fires on the fleet-standard
        // percentile triple at every call site, and only DomainMetrics escapes it via the
        // libs-runtime baseline.
        const val P50 = 0.5
        const val P95 = 0.95
        const val P99 = 0.99
    }
}
