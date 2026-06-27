// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.agent.infrastructure.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.agent.domain.model.ChatMessage
import com.openbank.agent.domain.model.ChatRole
import com.openbank.agent.domain.model.ModelDescriptor
import com.openbank.agent.domain.model.ModelRequest
import com.openbank.agent.domain.model.StopReason
import com.openbank.agent.domain.model.ToolSpec
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MockModelProviderTest {

    private val provider = MockModelProvider().apply { objectMapper = jacksonObjectMapper() }
    private val model = ModelDescriptor(id = "mock-echo", provider = "mock")

    private val tools = listOf(
        ToolSpec("get_account", "get account", emptyMap()),
        ToolSpec("get_account_balance", "get balance", emptyMap()),
        ToolSpec("get_account_by_iban", "by iban", emptyMap()),
        ToolSpec("list_transactions", "list tx", emptyMap()),
    )

    private fun req(userText: String, vararg extra: ChatMessage) = ModelRequest(
        model = "mock-echo",
        messages = listOf(ChatMessage(ChatRole.USER, userText)) + extra,
        tools = tools,
    )

    @Test
    fun `a uuid with balance intent drives the balance tool`() {
        runBlocking {
            val resp = provider.complete(model, req("what is the balance of 11111111-1111-1111-1111-111111111111"))
            assertThat(resp.stopReason).isEqualTo(StopReason.TOOL_USE)
            assertThat(resp.toolInvocations).singleElement()
            assertThat(resp.toolInvocations[0].name).isEqualTo("get_account_balance")
            assertThat(
                resp.toolInvocations[0].arguments["accountId"].asText(),
            ).isEqualTo("11111111-1111-1111-1111-111111111111")
        }
    }

    @Test
    fun `a bare uuid drives get_account`() {
        runBlocking {
            val resp = provider.complete(model, req("show me 22222222-2222-2222-2222-222222222222"))
            assertThat(resp.toolInvocations[0].name).isEqualTo("get_account")
        }
    }

    @Test
    fun `an iban drives the iban lookup`() {
        runBlocking {
            val resp = provider.complete(model, req("look up CZ6508000000192000145399 please"))
            assertThat(resp.toolInvocations[0].name).isEqualTo("get_account_by_iban")
            assertThat(resp.toolInvocations[0].arguments["iban"].asText()).isEqualTo("CZ6508000000192000145399")
        }
    }

    @Test
    fun `a tool result is summarised and finishes the turn`() {
        runBlocking {
            val resp = provider.complete(
                model,
                req(
                    "balance of 33333333-3333-3333-3333-333333333333",
                    ChatMessage(ChatRole.TOOL, "{\"available\":42}", toolCallId = "t1"),
                ),
            )
            assertThat(resp.stopReason).isEqualTo(StopReason.END)
            assertThat(resp.content).contains("42")
        }
    }

    @Test
    fun `nothing actionable yields a capability description`() {
        runBlocking {
            val resp = provider.complete(model, req("hello"))
            assertThat(resp.stopReason).isEqualTo(StopReason.END)
            assertThat(resp.toolInvocations).isEmpty()
            assertThat(resp.content).contains("get_account")
        }
    }
}
