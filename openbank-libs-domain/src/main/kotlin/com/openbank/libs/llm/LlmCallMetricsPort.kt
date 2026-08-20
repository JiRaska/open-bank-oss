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
     * @param provider the egress backend this attempt was ADDRESSED TO, from [providerOf] — the
     *   gateway or vendor host the request went to, not the upstream vendor LiteLLM may route it on
     *   to. A caller cannot observe the latter (that mapping lives in litellm-config), and a label
     *   that claims knowledge the emitter does not have is worse than a coarse one. Defaulted to
     *   [PROVIDER_UNKNOWN] so an un-migrated call site keeps compiling and stays honestly labelled
     *   rather than silently mislabelled. It does NOT change what [outcome] means: the two tags are
     *   independent, and every existing outcome vocabulary and alert keeps its meaning.
     */
    @Suppress("LongParameterList")
    fun recordCall(
        model: String,
        outcome: String,
        promptTokens: Int,
        completionTokens: Int,
        durationNanos: Long,
        provider: String = PROVIDER_UNKNOWN,
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

        // --- provider vocabulary (closed, like `outcome`) -------------------------------------
        //
        // Closed on purpose: this is a Prometheus label, and deriving it from a raw host would let
        // any endpoint string become a new series. Anything unrecognised collapses to
        // PROVIDER_OTHER, so a mis-set endpoint shows up as one extra series rather than a
        // cardinality leak — and PROVIDER_UNKNOWN specifically means "the call site has not been
        // migrated", which is a different fact from "the endpoint is not one we recognise".

        /** In-cluster LiteLLM gateway (ADR-0174/0175) — the fleet's single egress choke point. */
        const val PROVIDER_LITELLM = "litellm"
        const val PROVIDER_GROQ = "groq"
        const val PROVIDER_DEEPINFRA = "deepinfra"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_OLLAMA = "ollama"

        /** A recognised call, an endpoint host that is not in the list above. */
        const val PROVIDER_OTHER = "other"

        /** The call site does not report a provider yet. */
        const val PROVIDER_UNKNOWN = "unknown"

        /**
         * Classifies an OpenAI-compatible base URL into the closed vocabulary above.
         *
         * Substring matching on the host, not equality: the LiteLLM service is addressed as
         * `litellm.ai-platform:4000` by some callers and `litellm.ai-platform.svc:4000` by others,
         * and both are the same backend — a label that split them would answer "which provider"
         * with "which spelling of the DNS name someone happened to configure". Malformed input
         * yields [PROVIDER_OTHER] rather than throwing: a metrics helper may never break a call.
         */
        @Suppress("ReturnCount")
        fun providerOf(baseUrl: String): String {
            val host = runCatching { java.net.URI(baseUrl).host }.getOrNull()?.lowercase()
                ?: return PROVIDER_OTHER
            if (host.startsWith("litellm.") || host == "litellm") return PROVIDER_LITELLM
            if (host.contains("groq.com")) return PROVIDER_GROQ
            if (host.contains("deepinfra.com")) return PROVIDER_DEEPINFRA
            if (host.contains("openai.com")) return PROVIDER_OPENAI
            if (host.contains("ollama")) return PROVIDER_OLLAMA
            return PROVIDER_OTHER
        }

        /** Records nothing. The default for every caller that has not been wired. */
        val NONE: LlmCallMetricsPort = object : LlmCallMetricsPort {
            override fun recordCall(
                model: String,
                outcome: String,
                promptTokens: Int,
                completionTokens: Int,
                durationNanos: Long,
                provider: String,
            ) = Unit
        }
    }
}
