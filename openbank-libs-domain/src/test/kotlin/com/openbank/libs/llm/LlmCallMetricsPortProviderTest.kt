// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `provider` is a Prometheus label, so the only two properties that matter are that it CLASSIFIES
 * the endpoints the fleet actually configures, and that it cannot be made to emit an unbounded set
 * of values by a mis-set endpoint. Both are asserted here; the values used are the literal strings
 * from gitops (`http://litellm.ai-platform.svc:4000/v1`, `http://litellm.ai-platform:4000`) rather
 * than tidied-up equivalents, because the two spellings of the same service are exactly what a
 * naive host-equality implementation would split into two series.
 */
class LlmCallMetricsPortProviderTest {

    @Test
    fun `both spellings of the in-cluster gateway classify as one provider`() {
        assertThat(LlmCallMetricsPort.providerOf("http://litellm.ai-platform.svc:4000/v1"))
            .isEqualTo(LlmCallMetricsPort.PROVIDER_LITELLM)
        assertThat(LlmCallMetricsPort.providerOf("http://litellm.ai-platform:4000"))
            .isEqualTo(LlmCallMetricsPort.PROVIDER_LITELLM)
    }

    @Test
    fun `the direct-to-vendor endpoints the local-dev defaults still use are classified`() {
        // These are the application.yaml defaults a `quarkusDev` run takes, so a developer's calls
        // are not silently lumped in with the deployed gateway's.
        assertThat(LlmCallMetricsPort.providerOf("https://api.groq.com/openai/v1"))
            .isEqualTo(LlmCallMetricsPort.PROVIDER_GROQ)
        assertThat(LlmCallMetricsPort.providerOf("https://api.deepinfra.com/v1/openai"))
            .isEqualTo(LlmCallMetricsPort.PROVIDER_DEEPINFRA)
        assertThat(LlmCallMetricsPort.providerOf("https://api.openai.com/v1"))
            .isEqualTo(LlmCallMetricsPort.PROVIDER_OPENAI)
        assertThat(LlmCallMetricsPort.providerOf("http://ollama:11434/v1"))
            .isEqualTo(LlmCallMetricsPort.PROVIDER_OLLAMA)
    }

    @Test
    fun `an unrecognised or malformed endpoint collapses to one bounded value, never throws`() {
        // The negative case the label exists to survive: anything else must NOT become its own
        // series, and a metrics helper may never break the call it is describing.
        assertThat(LlmCallMetricsPort.providerOf("https://some-new-vendor.example/v1"))
            .isEqualTo(LlmCallMetricsPort.PROVIDER_OTHER)
        assertThat(LlmCallMetricsPort.providerOf("not a url at all"))
            .isEqualTo(LlmCallMetricsPort.PROVIDER_OTHER)
        assertThat(LlmCallMetricsPort.providerOf("")).isEqualTo(LlmCallMetricsPort.PROVIDER_OTHER)
    }

    @Test
    fun `an un-migrated call site is labelled unknown, not silently attributed to a provider`() {
        // "not reported" and "reported as something we did not recognise" are different facts, and
        // conflating them is how a half-migrated fleet reads as fully instrumented.
        assertThat(LlmCallMetricsPort.PROVIDER_UNKNOWN).isNotEqualTo(LlmCallMetricsPort.PROVIDER_OTHER)
        var seen: String? = null
        val probe = object : LlmCallMetricsPort {
            override fun recordCall(
                model: String,
                outcome: String,
                promptTokens: Int,
                completionTokens: Int,
                durationNanos: Long,
                provider: String,
            ) {
                seen = provider
            }
        }
        probe.recordCall("m", LlmCallMetricsPort.OUTCOME_SUCCESS, 0, 0, 0)
        assertThat(seen).isEqualTo(LlmCallMetricsPort.PROVIDER_UNKNOWN)
    }
}
