// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.agent.domain.model

import com.fasterxml.jackson.databind.JsonNode

/**
 * Provider-agnostic chat/completion contract (ADR-0031 D6, "model-agnostic via a gateway").
 *
 * Nothing here is specific to Anthropic, OpenAI, vLLM or any vendor: a [ModelProvider] adapter
 * translates these neutral types to and from a concrete wire format. Adding a new model is a
 * config entry; adding a new *kind* of backend is one adapter implementing [ModelProvider].
 */

enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * One turn in the conversation. [toolCalls] is set on an ASSISTANT turn that asks to invoke
 * tools; [toolCallId] ties a TOOL turn back to the invocation it answers.
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String = "",
    val toolCalls: List<ToolInvocation> = emptyList(),
    val toolCallId: String? = null,
)

/** A tool the model is allowed to call this turn — neutral mirror of an MCP tool definition. */
data class ToolSpec(val name: String, val description: String, val inputSchema: Map<String, Any>)

/** The model's request to run a tool. [arguments] is whatever JSON the model produced. */
data class ToolInvocation(val id: String, val name: String, val arguments: JsonNode)

enum class StopReason { END, TOOL_USE, MAX_TOKENS, FILTERED, ERROR }

data class ModelUsage(val inputTokens: Int = 0, val outputTokens: Int = 0)

/** A neutral completion request. [model] is the registry id, not a provider-specific name. */
data class ModelRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 1024,
    val temperature: Double = 0.0,
)

data class ModelResponse(
    val content: String = "",
    val toolInvocations: List<ToolInvocation> = emptyList(),
    val stopReason: StopReason = StopReason.END,
    val usage: ModelUsage = ModelUsage(),
    val modelId: String,
    val modelVersion: String = "unknown",
)

enum class Sensitivity { HOSTED, SELF_HOSTED }

/**
 * A registered model, assembled from config (`model-gateway.models[*]`). The gateway resolves a
 * request's `model` id to one of these, then dispatches to the [ModelProvider] whose key equals
 * [provider]. [sensitivity] drives routing — PII / money-path context must pin to SELF_HOSTED
 * (ADR-0031 D6); HOSTED is for general reasoning.
 */
data class ModelDescriptor(
    val id: String,
    val provider: String,
    val endpoint: String? = null,
    val sensitivity: Sensitivity = Sensitivity.HOSTED,
    val enabled: Boolean = true,
)
