// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.copilot.infrastructure.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.copilot.domain.model.ChatMessage
import com.openbank.copilot.domain.model.ChatRole
import com.openbank.copilot.domain.model.ModelDescriptor
import com.openbank.copilot.domain.model.ModelRequest
import com.openbank.copilot.domain.model.StopReason
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenAiCompatibleModelProviderTest {

    private val mapper = ObjectMapper().registerKotlinModule()
    private val provider = OpenAiCompatibleModelProvider().also { it.objectMapper = mapper }

    @Test
    fun `key is openai-compat`(): Unit = runBlocking {
        assertThat(provider.key).isEqualTo("openai-compat")
    }

    @Test
    fun `stop response maps to END stop reason`(): Unit = runBlocking {
        val json = """
            {"id":"chatcmpl-123","model":"meta/llama-3.1-70b-instruct",
             "choices":[{"message":{"role":"assistant","content":"Váš zůstatek je 1000 CZK."},
             "finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":10}}
        """.trimIndent()
        val descriptor = ModelDescriptor(id = "meta/llama-3.1-70b-instruct", provider = "openai-compat")
        val result = provider.parseResponse(descriptor, json)
        assertThat(result.content).isEqualTo("Váš zůstatek je 1000 CZK.")
        assertThat(result.usage.outputTokens).isEqualTo(10)
        assertThat(result.stopReason).isEqualTo(StopReason.END)
    }

    @Test
    fun `tool_calls finish_reason maps to TOOL_USE stop reason`(): Unit = runBlocking {
        val json = """
            {"id":"chatcmpl-456","model":"meta/llama-3.1-70b-instruct",
             "choices":[{"message":{"role":"assistant","content":null,
             "tool_calls":[{"id":"call_abc","type":"function",
             "function":{"name":"get_account_balance","arguments":"{\"accountId\":\"some-uuid\"}"}}]},
             "finish_reason":"tool_calls"}]}
        """.trimIndent()
        val descriptor = ModelDescriptor(id = "meta/llama-3.1-70b-instruct", provider = "openai-compat")
        val result = provider.parseResponse(descriptor, json)
        assertThat(result.stopReason).isEqualTo(StopReason.TOOL_USE)
        assertThat(result.toolInvocations).hasSize(1)
        assertThat(result.toolInvocations.first().name).isEqualTo("get_account_balance")
    }

    @Test
    fun `missing endpoint throws with clear message`(): Unit = runBlocking {
        val descriptor = ModelDescriptor(id = "test-model", provider = "openai-compat", endpoint = null)
        val request = ModelRequest(
            model = "test-model",
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
        )
        val ex = runCatching { provider.complete(descriptor, request) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalStateException::class.java)
        assertThat(ex?.message).contains("no endpoint")
    }
}
