// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.agent.infrastructure.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.agent.domain.model.ChatMessage
import com.openbank.agent.domain.model.ChatRole
import com.openbank.agent.domain.model.ModelDescriptor
import com.openbank.agent.domain.model.ModelRequest
import com.openbank.agent.domain.model.StopReason
import com.openbank.agent.domain.model.ToolInvocation
import com.openbank.agent.domain.model.ToolSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure translation tests for the OpenAI-compatible adapter — no network. They pin the two seams
 * that make tool calling work end-to-end against Groq/OpenRouter/vLLM/Ollama: the neutral request
 * -> OpenAI `/chat/completions` body, and the OpenAI response -> neutral [ModelResponse]. If either
 * drifts (e.g. arguments stop being a JSON *string*, or `finish_reason: tool_calls` stops mapping
 * to TOOL_USE) the governed reasoning loop silently stops calling MCP tools, so this guards it.
 */
class OpenAiCompatibleModelProviderTest {

    private val mapper = jacksonObjectMapper()
    private val provider = OpenAiCompatibleModelProvider().apply { objectMapper = mapper }
    private val model = ModelDescriptor(
        id = "llama-3.3-70b-versatile",
        provider = "openai-compat",
        endpoint = "https://api.groq.com/openai/v1",
    )

    @Test
    fun `request body maps roles, tools and tool_choice`() {
        val request = ModelRequest(
            model = model.id,
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, "be concise"),
                ChatMessage(ChatRole.USER, "balance of acct 7"),
            ),
            tools = listOf(
                ToolSpec(
                    name = "get_account_balance",
                    description = "read balance",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf("accountId" to mapOf("type" to "string")),
                    ),
                ),
            ),
            maxTokens = 512,
            temperature = 0.0,
        )

        val body = provider.buildRequestBody(model, request)

        assertThat(body.get("model").asText()).isEqualTo("llama-3.3-70b-versatile")
        assertThat(body.get("max_tokens").asInt()).isEqualTo(512)
        assertThat(body.get("messages")).hasSize(2)
        assertThat(body.get("messages")[0].get("role").asText()).isEqualTo("system")
        assertThat(body.get("messages")[1].get("role").asText()).isEqualTo("user")
        // tools are exposed as OpenAI function specs with the raw JSON schema under `parameters`.
        assertThat(body.get("tools")[0].get("type").asText()).isEqualTo("function")
        assertThat(body.get("tools")[0].get("function").get("name").asText()).isEqualTo("get_account_balance")
        assertThat(body.get("tools")[0].get("function").get("parameters").get("type").asText()).isEqualTo("object")
        assertThat(body.get("tool_choice").asText()).isEqualTo("auto")
    }

    @Test
    fun `assistant tool-call turn serialises arguments as a JSON string and null content`() {
        val args = mapper.readTree("""{"accountId":"acct-7"}""")
        val request = ModelRequest(
            model = model.id,
            messages = listOf(
                ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(ToolInvocation(id = "call_1", name = "get_account_balance", arguments = args)),
                ),
                ChatMessage(role = ChatRole.TOOL, content = """{"available":42}""", toolCallId = "call_1"),
            ),
        )

        val body = provider.buildRequestBody(model, request)
        val assistant = body.get("messages")[0]
        assertThat(assistant.get("content").isNull).isTrue()
        val call = assistant.get("tool_calls")[0]
        assertThat(call.get("id").asText()).isEqualTo("call_1")
        assertThat(call.get("type").asText()).isEqualTo("function")
        assertThat(call.get("function").get("name").asText()).isEqualTo("get_account_balance")
        // arguments MUST be a JSON string, not a nested object (OpenAI contract).
        assertThat(call.get("function").get("arguments").isTextual).isTrue()
        assertThat(call.get("function").get("arguments").asText()).isEqualTo("""{"accountId":"acct-7"}""")

        val tool = body.get("messages")[1]
        assertThat(tool.get("role").asText()).isEqualTo("tool")
        assertThat(tool.get("tool_call_id").asText()).isEqualTo("call_1")
    }

    @Test
    fun `tool_calls response maps to TOOL_USE with parsed arguments`() {
        val json = """
            {
              "model": "llama-3.3-70b-versatile",
              "choices": [{
                "finish_reason": "tool_calls",
                "message": {
                  "content": null,
                  "tool_calls": [{
                    "id": "call_abc",
                    "type": "function",
                    "function": {"name": "get_account", "arguments": "{\"accountId\":\"acct-9\"}"}
                  }]
                }
              }],
              "usage": {"prompt_tokens": 31, "completion_tokens": 12}
            }
        """.trimIndent()

        val resp = provider.parseResponse(model, json)

        assertThat(resp.stopReason).isEqualTo(StopReason.TOOL_USE)
        assertThat(resp.toolInvocations).singleElement()
        assertThat(resp.toolInvocations[0].id).isEqualTo("call_abc")
        assertThat(resp.toolInvocations[0].name).isEqualTo("get_account")
        assertThat(resp.toolInvocations[0].arguments.get("accountId").asText()).isEqualTo("acct-9")
        assertThat(resp.usage.inputTokens).isEqualTo(31)
        assertThat(resp.usage.outputTokens).isEqualTo(12)
        assertThat(resp.modelVersion).isEqualTo("llama-3.3-70b-versatile")
    }

    @Test
    fun `plain stop response maps to END with content`() {
        val json = """
            {
              "model": "llama-3.3-70b-versatile",
              "choices": [{"finish_reason": "stop", "message": {"content": "The balance is 42 CZK."}}],
              "usage": {"prompt_tokens": 50, "completion_tokens": 8}
            }
        """.trimIndent()

        val resp = provider.parseResponse(model, json)

        assertThat(resp.stopReason).isEqualTo(StopReason.END)
        assertThat(resp.content).isEqualTo("The balance is 42 CZK.")
        assertThat(resp.toolInvocations).isEmpty()
    }
}
