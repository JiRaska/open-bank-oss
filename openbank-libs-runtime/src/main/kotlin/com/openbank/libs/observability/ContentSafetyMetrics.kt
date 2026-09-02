// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import com.openbank.libs.llm.ContentSafetyMetricsPort
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Micrometer implementation of [ContentSafetyMetricsPort] — the guardrail's VERDICT series
 * (ADR-0031 guardrails; the call-level series lives in [LlmCallMetrics]).
 *
 * One counter, `openbank.guardrail.classifications{model,role,decision,blocked}`, all four tags
 * closed vocabularies. What it is for is the failure this repo keeps re-discovering: a control that
 * is up, green and classifying nothing. `decision="unavailable"` at ~100 % of volume is that state,
 * and it is invisible in an error-rate view because an unconfigured guardrail makes no failing
 * calls at all — it makes none. The alert to write over this series is therefore about the SUCCESS
 * state (ratio of real verdicts), not about errors.
 *
 * `blocked` is separate from `decision` because they diverge by design: the same `unavailable`
 * verdict blocks on a money-path caller (fail closed) and does not on the help surface, and only
 * the pair tells you which policy actually ran.
 *
 * Same CDI shape as its siblings: `Instance<MeterRegistry>` so the bean loads harmlessly in a
 * service with no `quarkus-micrometer-*`, where every method becomes a silent no-op.
 */
@ApplicationScoped
class ContentSafetyMetrics : ContentSafetyMetricsPort {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private fun reg(): MeterRegistry? = if (registryInstance.isResolvable) registryInstance.get() else null

    override fun recordClassification(model: String, role: String, decision: String, blocked: Boolean) {
        val registry = reg() ?: return
        Counter.builder("openbank.guardrail.classifications")
            .tags("model", model, "role", role, "decision", decision, "blocked", blocked.toString())
            .register(registry)
            .increment()
    }
}
