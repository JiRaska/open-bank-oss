// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.llm

/**
 * What every [ContentSafetyPort] classification reports to Prometheus.
 *
 * Separate from [LlmCallMetricsPort] on purpose: that one measures the CALL (tokens, latency,
 * transport outcome), this one measures the VERDICT. They answer different questions and the
 * interesting alert lives here — a guardrail whose decisions are 100 % `unavailable` is a control
 * outage, and it is invisible in a call-latency series because the call may be failing fast and
 * cheaply. This repo has shipped the same shape twice (an adapter counting skipped pushes as
 * delivered; a liveness gauge nobody re-derived at t=0), so the decision gets its own series.
 *
 * Implementations must never throw: a metrics failure may not break a guardrail.
 */
interface ContentSafetyMetricsPort {

    /**
     * @param model model id as sent upstream, e.g. `meta-llama/llama-guard-4-12b`.
     * @param role `user` | `assistant` — which side of the conversation was judged.
     * @param decision `safe` | `unsafe` | `unavailable` (lowercase [ContentSafetyPort.Decision]).
     *   Closed vocabulary; this is a Prometheus label.
     * @param blocked whether the caller's policy turned this verdict into a refusal. Distinct from
     *   `decision`: the same `unavailable` verdict blocks on money-path and does not on help.
     */
    fun recordClassification(model: String, role: String, decision: String, blocked: Boolean)

    companion object {
        /** Records nothing. Default for callers not yet wired. */
        val NONE: ContentSafetyMetricsPort = object : ContentSafetyMetricsPort {
            override fun recordClassification(model: String, role: String, decision: String, blocked: Boolean) = Unit
        }
    }
}
