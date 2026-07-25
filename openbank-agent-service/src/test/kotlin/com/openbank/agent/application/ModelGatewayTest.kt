// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.agent.application.port.out.ModelProvider
import com.openbank.agent.domain.model.ChatMessage
import com.openbank.agent.domain.model.ChatRole
import com.openbank.agent.domain.model.ModelRequest
import com.openbank.agent.domain.model.StopReason
import com.openbank.agent.infrastructure.model.MockModelProvider
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

class ModelGatewayTest {

    private val auditPublisher = mockk<AuditEventPublisher>().also {
        coEvery { it.publish(any()) } returns Unit
    }

    private fun entry(id: String, provider: String, sensitivity: String) = mockk<ModelGatewayConfig.ModelEntry>().also {
        every { it.id() } returns id
        every { it.provider() } returns provider
        every { it.endpoint() } returns Optional.empty()
        every { it.sensitivity() } returns sensitivity
        every { it.enabled() } returns true
    }

    private fun gateway(default: String, vararg entries: ModelGatewayConfig.ModelEntry): ModelGateway {
        val config = mockk<ModelGatewayConfig>().also {
            every { it.defaultModel() } returns default
            every { it.models() } returns entries.toList()
        }
        val mock = MockModelProvider().apply { objectMapper = jacksonObjectMapper() }
        val providers = mockk<Instance<ModelProvider>>()
        every { providers.iterator() } returns mutableListOf<ModelProvider>(mock).iterator()
        return ModelGateway().apply {
            this.config = config
            this.providers = providers
            this.auditPublisher = this@ModelGatewayTest.auditPublisher
        }.also { it.init() }
    }

    private fun req(text: String) = ModelRequest(model = "", messages = listOf(ChatMessage(ChatRole.USER, text)))

    @Test
    fun `resolves the default model and audits the completion`() {
        runBlocking {
            val gw = gateway("mock-echo", entry("mock-echo", "mock", "hosted"))
            val event = slot<AuditEvent>()
            coEvery { auditPublisher.publish(capture(event)) } returns Unit

            val resp = gw.complete(null, req("hello"))

            assertThat(resp.modelId).isEqualTo("mock-echo")
            assertThat(resp.stopReason).isEqualTo(StopReason.END)
            coVerify { auditPublisher.publish(any()) }
            assertThat(event.captured.actorType).isEqualTo("AI_AGENT")
            assertThat(event.captured.result).isEqualTo(AuditResult.SUCCESS)
            assertThat(event.captured.payload["prompt_hash"] as String).hasSize(64)
        }
    }

    @Test
    fun `an unknown model id fails loudly`() {
        val gw = gateway("mock-echo", entry("mock-echo", "mock", "hosted"))
        assertThatThrownBy { runBlocking { gw.complete("ghost", req("hi")) } }
            .hasMessageContaining("unknown model")
    }

    @Test
    fun `sensitive context routes to a self-hosted model`() {
        runBlocking {
            val gw = gateway(
                "hosted-1",
                entry("hosted-1", "mock", "hosted"),
                entry("local-1", "mock", "self-hosted"),
            )
            val resp = gw.complete("hosted-1", req("hello"), sensitive = true)
            assertThat(resp.modelId).isEqualTo("local-1")
        }
    }

    @Test
    fun `sensitive context with no self-hosted model fails closed`() {
        assertThatThrownBy {
            runBlocking {
                gateway(
                    "hosted-1",
                    entry("hosted-1", "mock", "hosted"),
                ).complete("hosted-1", req("hi"), sensitive = true)
            }
        }.hasMessageContaining("no self-hosted model")
    }
}
