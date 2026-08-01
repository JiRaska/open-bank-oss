// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.llm

/**
 * What every LLM call in the fleet reports, so the AI spend and reliability are observable in
 * Prometheus rather than only in a span attribute or a provider's billing page.
 *
 * Why a port and not a direct MeterRegistry call at each site: three separate code paths make real
 * `/chat/completions` calls — the shared [LlmGatewayPort] client (devops-agent,
 * control-liveness-sentinel), agent-service's `OpenAiCompatibleModelProvider`, and
 * copilot-service's. They live in different modules with different licences and none of them may
 * import Micrometer into a domain type. One pure port implemented once in `openbank-libs-runtime`
 * keeps the metric NAMES and TAGS identical across all three, which is the only reason a fleet-wide
 * "tokens by model" query can exist at all.
 *
 * The gap this closes: LiteLLM's own Prometheus callback is an Enterprise feature, so the gateway
 * cannot report this for us — measured against the upstream docs before choosing client-side
 * instrumentation. agent-service did record `openbank.agent.tokens_total`, but as an OpenTelemetry
 * **span attribute**, which lands in Tempo and is therefore unavailable to a Prometheus alert or a
 * budget rule.
 *
 * Implementations must never throw: a metrics failure may not break an LLM call. The no-op [NONE]
 * is the default everywhere, so a caller that has not been wired yet is silent rather than broken.
 */
interface LlmCallMetricsPort {

    /**
     * One completed call attempt, successful or not.
     *
     * @param model the model id as sent upstream (e.g. `deepseek-ai/DeepSeek-V3.2`) — the same
     *   string the gateway config uses, so a cost rule can join on it without a mapping table.
     * @param outcome one of [OUTCOME_SUCCESS], [OUTCOME_HTTP_ERROR], [OUTCOME_EXCEPTION],
     *   [OUTCOME_NOT_CONFIGURED]. A closed vocabulary: this tag is a Prometheus label and an
     *   open-ended one (an exception message, a status code) would be a cardinality bomb.
     * @param promptTokens from the response's `usage.prompt_tokens`; 0 when the provider omits it.
     * @param completionTokens from `usage.completion_tokens`; 0 when the provider omits it.
     * @param durationNanos wall-clock for the whole attempt, including a failed one — a timeout is
     *   the slowest and most interesting case, so timing only successes would hide it.
     */
    fun recordCall(
        model: String,
        outcome: String,
        promptTokens: Int,
        completionTokens: Int,
        durationNanos: Long,
    )

    companion object {
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_HTTP_ERROR = "http_error"
        const val OUTCOME_EXCEPTION = "exception"

        /**
         * The call never left the process because no API key was seeded. Distinct from an error on
         * purpose: it is the fleet's documented degraded mode (an unseeded key returns `null`
         * rather than CrashLooping the pod), and folding it into `exception` would make a
         * never-configured agent look like a broken one — and vice versa, which is worse.
         */
        const val OUTCOME_NOT_CONFIGURED = "not_configured"

        /** Records nothing. The default for every caller that has not been wired. */
        val NONE: LlmCallMetricsPort = object : LlmCallMetricsPort {
            override fun recordCall(
                model: String,
                outcome: String,
                promptTokens: Int,
                completionTokens: Int,
                durationNanos: Long,
            ) = Unit
        }
    }
}
