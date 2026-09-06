// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.model

import com.openbank.libs.llm.ContentSafetyMetricsPort
import com.openbank.libs.llm.ContentSafetyPort
import com.openbank.libs.llm.LlamaGuardContentSafetyAdapter
import com.openbank.libs.llm.LlmCallMetricsPort
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Which [ContentSafetyPort] the service actually gets. The dangerous outcome is an unwired
 * guardrail that looks like a clean bill of health, so an off or endpoint-less configuration must
 * yield the DISABLED port (which reports UNAVAILABLE, never SAFE) and never a live adapter.
 */
class AgentContentSafetyProducerTest {

    private fun producer(enabled: Boolean, endpoint: String?, apiKey: String? = "k") =
        AgentContentSafetyProducer(
            safetyMetrics = mockk<ContentSafetyMetricsPort>(relaxed = true),
            callMetrics = mockk<LlmCallMetricsPort>(relaxed = true),
            enabled = enabled,
            endpoint = Optional.ofNullable(endpoint),
            model = "meta-llama/llama-guard-4-12b",
            apiKey = Optional.ofNullable(apiKey),
        )

    @Test
    fun `disabled by flag produces the DISABLED port`() {
        assertThat(producer(enabled = false, endpoint = "https://litellm.internal").contentSafety())
            .isSameAs(ContentSafetyPort.DISABLED)
    }

    @Test
    fun `enabled with an absent, empty or whitespace endpoint still produces DISABLED`() {
        assertThat(producer(true, null).contentSafety()).isSameAs(ContentSafetyPort.DISABLED)
        assertThat(producer(true, "").contentSafety()).isSameAs(ContentSafetyPort.DISABLED)
        assertThat(producer(true, "   ").contentSafety()).isSameAs(ContentSafetyPort.DISABLED)
    }

    @Test
    fun `the DISABLED port reports unavailable rather than safe, so the gap is visible in metrics`(): Unit =
        runBlocking {
            val verdict = producer(false, null).contentSafety()
                .classify(ContentSafetyPort.SafetyRole.USER, "how do I launder money")

            assertThat(verdict.decision).isEqualTo(ContentSafetyPort.Decision.UNAVAILABLE)
        }

    @Test
    fun `enabled with an endpoint produces the live Llama Guard adapter`() {
        val port = producer(true, "https://litellm.internal ").contentSafety()

        assertThat(port).isInstanceOf(LlamaGuardContentSafetyAdapter::class.java)
        assertThat(port).isNotSameAs(ContentSafetyPort.DISABLED)
    }

    @Test
    fun `a missing api key does not silently disable the guardrail`() {
        val port = producer(true, "https://litellm.internal", apiKey = null).contentSafety()

        assertThat(port).isInstanceOf(LlamaGuardContentSafetyAdapter::class.java)
    }
}
