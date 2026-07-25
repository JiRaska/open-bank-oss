// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.application

import com.openbank.copilot.application.port.out.ModelProvider
import com.openbank.copilot.domain.model.ChatMessage
import com.openbank.copilot.domain.model.ChatRole
import com.openbank.copilot.domain.model.ModelRequest
import com.openbank.copilot.domain.model.ModelResponse
import com.openbank.copilot.domain.model.Sensitivity
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

/**
 * [ModelGateway] is the single trust-boundary seam every model call passes through (ADR-0089 D6):
 * it resolves the requested model, force-routes sensitive (PII/money-path) requests to a
 * self-hosted model, and audits every completion attributed to the customer — never the gateway
 * itself. All of that was untested.
 */
class ModelGatewayTest {

    private fun entry(
        id: String,
        provider: String = "mock",
        sensitivity: String = "hosted",
        enabled: Boolean = true,
    ): ModelGatewayConfig.ModelEntry = mockk {
        every { this@mockk.id() } returns id
        every { this@mockk.provider() } returns provider
        every { this@mockk.endpoint() } returns Optional.empty()
        every { this@mockk.sensitivity() } returns sensitivity
        every { this@mockk.enabled() } returns enabled
    }

    private fun stubProvider(key: String, response: ModelResponse): ModelProvider = mockk {
        every { this@mockk.key } returns key
        coEvery { complete(any(), any()) } returns response
    }

    private fun gatewayWith(
        entries: List<ModelGatewayConfig.ModelEntry>,
        providers: List<ModelProvider>,
        auditPublisher: AuditEventPublisher = mockk(relaxed = true),
        defaultModel: String = "mock-echo",
    ): ModelGateway {
        val config = mockk<ModelGatewayConfig> {
            every { models() } returns entries
            every { this@mockk.defaultModel() } returns defaultModel
        }
        val providerInstance = mockk<Instance<ModelProvider>> {
            every { iterator() } answers { providers.toMutableList().iterator() }
        }
        return ModelGateway().apply {
            this.config = config
            this.providers = providerInstance
            this.auditPublisher = auditPublisher
            init()
        }
    }

    @Test
    fun `seeds a mock descriptor when no models are configured so the sandbox still boots`() {
        val gateway = gatewayWith(entries = emptyList(), providers = emptyList(), defaultModel = "mock-echo")

        val models = gateway.availableModels()

        assertThat(models).hasSize(1)
        assertThat(models.single().id).isEqualTo("mock-echo")
        assertThat(models.single().provider).isEqualTo("mock")
    }

    @Test
    fun `builds the registry from enabled config entries and filters out disabled ones`() {
        val gateway = gatewayWith(
            entries = listOf(entry("m1", enabled = true), entry("m2", enabled = false)),
            providers = emptyList(),
        )

        assertThat(gateway.availableModels().map { it.id }).containsExactly("m1")
    }

    @Test
    fun `parses self-hosted sensitivity from config, defaulting anything else to hosted`() {
        val gateway = gatewayWith(
            entries = listOf(entry("m1", sensitivity = "self-hosted"), entry("m2", sensitivity = "bogus")),
            providers = emptyList(),
        )

        val byId = gateway.availableModels().associateBy { it.id }
        assertThat(byId.getValue("m1").sensitivity).isEqualTo(Sensitivity.SELF_HOSTED)
        assertThat(byId.getValue("m2").sensitivity).isEqualTo(Sensitivity.HOSTED)
    }

    @Test
    fun `defaultModelId delegates to config`() {
        val gateway = gatewayWith(entries = emptyList(), providers = emptyList(), defaultModel = "the-default")
        assertThat(gateway.defaultModelId()).isEqualTo("the-default")
    }

    @Test
    fun `complete dispatches to the provider matching the descriptor and audits SUCCESS`(): Unit = runBlocking {
        val response = ModelResponse(content = "hi", modelId = "m1")
        val provider = stubProvider("mock", response)
        val auditPublisher = mockk<AuditEventPublisher>(relaxed = true)
        val eventSlot = slot<AuditEvent>()
        coEvery { auditPublisher.publish(capture(eventSlot)) } returns Unit
        val gateway = gatewayWith(
            entries = listOf(entry("m1")),
            providers = listOf(provider),
            auditPublisher = auditPublisher,
        )
        val request = ModelRequest(model = "ignored", messages = listOf(ChatMessage(ChatRole.USER, "hello")))

        val result = gateway.complete(modelId = "m1", request = request, actorId = "party-1")

        assertThat(result).isEqualTo(response)
        assertThat(eventSlot.captured.actorId).isEqualTo("party-1")
        assertThat(eventSlot.captured.actorType).isEqualTo("AI_AGENT")
        assertThat(eventSlot.captured.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(eventSlot.captured.resourceId).isEqualTo("m1")
        assertThat(eventSlot.captured.payload["model_id"]).isEqualTo("m1")
        // prompt content itself must never appear in the audit payload (PII) — only its hash.
        assertThat(eventSlot.captured.payload.values.map { it.toString() }).noneMatch { it.contains("hello") }
    }

    @Test
    fun `complete audits FAILURE and rethrows when the provider throws`(): Unit = runBlocking {
        val provider = mockk<ModelProvider> {
            every { key } returns "mock"
            coEvery { complete(any(), any()) } throws IllegalStateException("upstream down")
        }
        val auditPublisher = mockk<AuditEventPublisher>(relaxed = true)
        val eventSlot = slot<AuditEvent>()
        coEvery { auditPublisher.publish(capture(eventSlot)) } returns Unit
        val gateway =
            gatewayWith(entries = listOf(entry("m1")), providers = listOf(provider), auditPublisher = auditPublisher)
        val request = ModelRequest(model = "ignored", messages = emptyList())

        assertThatThrownBy {
            runBlocking { gateway.complete(modelId = "m1", request = request, actorId = "party-1") }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(eventSlot.captured.result).isEqualTo(AuditResult.FAILURE)
        assertThat(eventSlot.captured.payload["stop_reason"]).isEqualTo("ERROR")
    }

    @Test
    fun `complete falls back to the default model when modelId is null or blank`(): Unit = runBlocking {
        val provider = stubProvider("mock", ModelResponse(modelId = "mock-echo"))
        val gateway = gatewayWith(
            entries = listOf(entry("mock-echo")),
            providers = listOf(provider),
            defaultModel = "mock-echo",
        )
        val request = ModelRequest(model = "ignored", messages = emptyList())

        gateway.complete(modelId = null, request = request, actorId = "party-1")
        gateway.complete(modelId = "   ", request = request, actorId = "party-1")

        coVerify(exactly = 2) { provider.complete(match { it.id == "mock-echo" }, any()) }
    }

    @Test
    fun `complete throws for an unknown model id instead of silently falling back`(): Unit = runBlocking {
        val gateway =
            gatewayWith(
                entries = listOf(entry("m1")),
                providers = listOf(stubProvider("mock", ModelResponse(modelId = "m1"))),
            )
        val request = ModelRequest(model = "ignored", messages = emptyList())

        assertThatThrownBy {
            runBlocking { gateway.complete(modelId = "does-not-exist", request = request, actorId = "party-1") }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `sensitive requests are routed to the self-hosted model even when a different one was requested`(): Unit =
        runBlocking {
            val hostedProvider = stubProvider("mock", ModelResponse(modelId = "hosted-model"))
            val selfHostedProvider = mockk<ModelProvider> {
                every { key } returns "self-hosted-provider"
                coEvery { complete(any(), any()) } returns ModelResponse(modelId = "self-hosted-model")
            }
            val gateway = gatewayWith(
                entries = listOf(
                    entry("hosted-model", provider = "mock", sensitivity = "hosted"),
                    entry("self-hosted-model", provider = "self-hosted-provider", sensitivity = "self-hosted"),
                ),
                providers = listOf(hostedProvider, selfHostedProvider),
            )
            val request = ModelRequest(model = "ignored", messages = emptyList())

            gateway.complete(modelId = "hosted-model", request = request, sensitive = true, actorId = "party-1")

            coVerify(exactly = 0) { hostedProvider.complete(any(), any()) }
            coVerify { selfHostedProvider.complete(match { it.id == "self-hosted-model" }, any()) }
        }

    @Test
    fun `sensitive request with no self-hosted model registered fails closed instead of using a hosted model`(): Unit =
        runBlocking {
            val hostedProvider = stubProvider("mock", ModelResponse(modelId = "hosted-model"))
            val gateway = gatewayWith(entries = listOf(entry("hosted-model")), providers = listOf(hostedProvider))
            val request = ModelRequest(model = "ignored", messages = emptyList())

            assertThatThrownBy {
                runBlocking {
                    gateway.complete(modelId = "hosted-model", request = request, sensitive = true, actorId = "party-1")
                }
            }.isInstanceOf(IllegalStateException::class.java)
            coVerify(exactly = 0) { hostedProvider.complete(any(), any()) }
        }

    @Test
    fun `completeStream dispatches to the provider and audits after the stream completes`(): Unit = runBlocking {
        val response = ModelResponse(content = "streamed", modelId = "m1")
        val provider = mockk<ModelProvider> {
            every { key } returns "mock"
            coEvery { completeStream(any(), any(), any()) } returns response
        }
        val auditPublisher = mockk<AuditEventPublisher>(relaxed = true)
        val gateway =
            gatewayWith(entries = listOf(entry("m1")), providers = listOf(provider), auditPublisher = auditPublisher)
        val request = ModelRequest(model = "ignored", messages = emptyList())
        val chunks = mutableListOf<String>()

        val result = gateway.completeStream(modelId = "m1", request = request, actorId = "party-1") { chunks.add(it) }

        assertThat(result).isEqualTo(response)
        coVerify { auditPublisher.publish(match { it.result == AuditResult.SUCCESS }) }
    }
}
