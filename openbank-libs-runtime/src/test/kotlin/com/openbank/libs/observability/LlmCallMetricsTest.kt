// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import com.openbank.libs.llm.LlmCallMetricsPort
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The metric NAMES and TAGS here are a contract with things outside this module:
 * `prometheus-rules-ai.yaml` alerts on them, `dashboard-openbank-ai.yaml` plots them, and the
 * `openbank:llm_price_usd_per_token` price book joins on `(model, kind)`. Rename a tag and every
 * one of those goes quietly empty — a dashboard with no data looks exactly like a fleet making no
 * LLM calls, which is the misreading this whole feature exists to end.
 *
 * promtool can prove those queries PARSE; only this test can prove they will match anything. So the
 * assertions below deliberately spell the series out rather than referencing constants.
 */
class LlmCallMetricsTest {

    private fun withRegistry(reg: MeterRegistry?): LlmCallMetrics {
        val inst = mockk<Instance<MeterRegistry>>()
        if (reg == null) {
            every { inst.isResolvable } returns false
        } else {
            every { inst.isResolvable } returns true
            every { inst.get() } returns reg
        }
        return LlmCallMetrics().apply { registryInstance = inst }
    }

    @Test
    fun `a successful call emits the three series the rules and dashboard query`() {
        val reg = SimpleMeterRegistry()
        withRegistry(reg).recordCall(
            model = "deepseek-ai/DeepSeek-V3.2",
            outcome = LlmCallMetricsPort.OUTCOME_SUCCESS,
            promptTokens = 120,
            completionTokens = 45,
            durationNanos = 250_000_000,
        )

        assertThat(
            reg.find("openbank.llm.requests")
                .tag("model", "deepseek-ai/DeepSeek-V3.2").tag("outcome", "success")
                .counter()!!.count(),
        ).isEqualTo(1.0)

        // prompt and completion MUST be separate series: every provider prices them differently,
        // so a combined total cannot be costed at all, and the price book joins on (model, kind).
        assertThat(
            reg.find("openbank.llm.tokens")
                .tag("model", "deepseek-ai/DeepSeek-V3.2").tag("kind", "prompt")
                .counter()!!.count(),
        ).isEqualTo(120.0)
        assertThat(
            reg.find("openbank.llm.tokens")
                .tag("model", "deepseek-ai/DeepSeek-V3.2").tag("kind", "completion")
                .counter()!!.count(),
        ).isEqualTo(45.0)

        val timer = reg.find("openbank.llm.call.duration")
            .tag("model", "deepseek-ai/DeepSeek-V3.2").tag("outcome", "success").timer()
        assertThat(timer).isNotNull
        assertThat(timer!!.count()).isEqualTo(1L)
    }

    @Test
    fun `a failed call is still timed, because a timeout is the most interesting case`() {
        val reg = SimpleMeterRegistry()
        withRegistry(reg).recordCall(
            model = "llama-3.3-70b-versatile",
            outcome = LlmCallMetricsPort.OUTCOME_EXCEPTION,
            promptTokens = 0,
            completionTokens = 0,
            durationNanos = 60_000_000_000,
        )

        val timer = reg.find("openbank.llm.call.duration")
            .tag("model", "llama-3.3-70b-versatile").tag("outcome", "exception").timer()
        assertThat(timer).isNotNull
        assertThat(timer!!.count()).isEqualTo(1L)
        // Timing only successes would have hidden exactly the 60s case this asserts.
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isGreaterThan(59.0)
    }

    @Test
    fun `zero-token calls create no token series at all`() {
        val reg = SimpleMeterRegistry()
        withRegistry(reg).recordCall(
            model = "llama-3.3-70b-versatile",
            outcome = LlmCallMetricsPort.OUTCOME_HTTP_ERROR,
            promptTokens = 0,
            completionTokens = 0,
            durationNanos = 1_000,
        )

        // NOT "a counter at 0". Providers omit `usage` on an error, and a series pinned at zero
        // reads as "no spend" on a dashboard, whereas an absent one reads as "no data" — which is
        // the truth. This is the same distinction that made the business dashboard's flat lines
        // convincing for months.
        assertThat(reg.find("openbank.llm.tokens").counters()).isEmpty()
        // The request itself is still counted, or the error rate would have no denominator.
        assertThat(reg.find("openbank.llm.requests").tag("outcome", "http_error").counter())
            .isNotNull
    }

    @Test
    fun `not_configured is its own outcome, not folded into an error`() {
        val reg = SimpleMeterRegistry()
        val metrics = withRegistry(reg)
        metrics.recordCall("m", LlmCallMetricsPort.OUTCOME_NOT_CONFIGURED, 0, 0, 0)
        metrics.recordCall("m", LlmCallMetricsPort.OUTCOME_EXCEPTION, 0, 0, 5)

        // AiCallErrorRateHigh divides by {outcome!="not_configured"}. If these collapsed into one
        // outcome, a fleet with no API key seeded would read as 100% broken instead of "switched
        // off", and the two need different fixes.
        assertThat(reg.find("openbank.llm.requests").tag("outcome", "not_configured").counter()!!.count())
            .isEqualTo(1.0)
        assertThat(reg.find("openbank.llm.requests").tag("outcome", "exception").counter()!!.count())
            .isEqualTo(1.0)
    }

    @Test
    fun `with no MeterRegistry on the classpath every call is a silent no-op`() {
        // libs-runtime is consumed by services that ship no micrometer. Throwing here would take
        // down an LLM call over telemetry, which is never an acceptable trade.
        withRegistry(null).recordCall("m", LlmCallMetricsPort.OUTCOME_SUCCESS, 1, 1, 1)
    }

    @Test
    fun `provider tags all three series without changing what outcome means`() {
        val reg = SimpleMeterRegistry()
        withRegistry(reg).recordCall(
            model = "deepseek-ai/DeepSeek-V3.2",
            outcome = LlmCallMetricsPort.OUTCOME_SUCCESS,
            promptTokens = 7,
            completionTokens = 3,
            durationNanos = 1_000_000,
            provider = LlmCallMetricsPort.PROVIDER_LITELLM,
        )

        // All three, not just requests: a cost figure that cannot be split by egress backend cannot
        // answer "which gateway is the spend going through", which is the question #5736 opened on.
        assertThat(reg.find("openbank.llm.requests").tag("provider", "litellm").counter()!!.count())
            .isEqualTo(1.0)
        assertThat(reg.find("openbank.llm.tokens").tag("provider", "litellm").counters()).hasSize(2)
        assertThat(reg.find("openbank.llm.call.duration").tag("provider", "litellm").timer()).isNotNull

        // The point of the negative half: `outcome` still selects exactly what it selected before,
        // so AiCallErrorRateHigh and the dashboard's outcome="success" filter are untouched.
        assertThat(reg.find("openbank.llm.requests").tag("outcome", "success").counter()!!.count())
            .isEqualTo(1.0)
    }

    @Test
    fun `an un-migrated call site still emits, labelled unknown`() {
        // The default must keep the series alive rather than dropping the call: a migration that
        // silences a metric while it is half-done is worse than a coarse label.
        val reg = SimpleMeterRegistry()
        withRegistry(reg).recordCall("m", LlmCallMetricsPort.OUTCOME_HTTP_ERROR, 0, 0, 1)
        assertThat(reg.find("openbank.llm.requests").tag("provider", "unknown").counter()!!.count())
            .isEqualTo(1.0)
    }

    @Test
    fun `the no-op port records nothing and does not throw`() {
        // The default handed to any caller not yet wired. It must be inert, not partially wired.
        LlmCallMetricsPort.NONE.recordCall("m", LlmCallMetricsPort.OUTCOME_SUCCESS, 10, 10, 10)
    }

    @Test
    fun `tokens the provider never reported are counted as unmeasured, not as a zero`() {
        // The streaming path (#5878) often has no usage chunk to read, and `0` there would be
        // indistinguishable from a free call. TOKENS_UNKNOWN must add nothing to the token series
        // AND must leave a positive trace of its own, which is what AiSpendUnmeasured alerts on.
        val reg = SimpleMeterRegistry()
        withRegistry(reg).recordCall(
            model = "deepseek-ai/DeepSeek-V3.2",
            outcome = LlmCallMetricsPort.OUTCOME_SUCCESS,
            promptTokens = LlmCallMetricsPort.TOKENS_UNKNOWN,
            completionTokens = LlmCallMetricsPort.TOKENS_UNKNOWN,
            durationNanos = 1_000,
            provider = LlmCallMetricsPort.PROVIDER_LITELLM,
        )

        assertThat(reg.find("openbank.llm.tokens").counters()).isEmpty()
        assertThat(
            reg.find("openbank.llm.tokens.unreported")
                .tag("model", "deepseek-ai/DeepSeek-V3.2")
                .tag("provider", "litellm")
                .tag("outcome", "success")
                .counter()!!.count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `a call with real token counts leaves no unmeasured trace`() {
        // The negative control: without it the assertion above passes against an implementation
        // that counts EVERY call as unmeasured, which would make the alert fire constantly.
        val reg = SimpleMeterRegistry()
        withRegistry(reg).recordCall(
            model = "deepseek-ai/DeepSeek-V3.2",
            outcome = LlmCallMetricsPort.OUTCOME_SUCCESS,
            promptTokens = 12,
            completionTokens = 3,
            durationNanos = 1_000,
            provider = LlmCallMetricsPort.PROVIDER_LITELLM,
        )

        assertThat(reg.find("openbank.llm.tokens.unreported").counters()).isEmpty()
    }
}
