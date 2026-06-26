// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.copilot.domain.model

import com.fasterxml.jackson.databind.JsonNode

/**
 * Provider-agnostic chat/completion contract (ADR-0089 D6, model-agnostic via a gateway —
 * mirrors the agent-service seam, ADR-0031 D6).
 *
 * Nothing here is specific to any vendor: a [ModelProvider] adapter translates these neutral
 * types to and from a concrete wire format. Adding a model is a config entry; adding a new *kind*
 * of backend is one adapter. The sandbox uses a mock/free provider (synthetic data only);
 * production pins to an in-cluster or EU zero-retention model — config, never code.
 */

enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

/** One turn. [toolCalls] is set on an ASSISTANT turn that asks to invoke tools (Phase 2). */
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
 * A registered model, assembled from config (`copilot.model-gateway.models[*]`). [sensitivity]
 * drives routing — customer PII / money-path context pins to SELF_HOSTED in production
 * (ADR-0089 D6); the sandbox runs a HOSTED free/mock model over synthetic data only.
 */
data class ModelDescriptor(
    val id: String,
    val provider: String,
    val endpoint: String? = null,
    val sensitivity: Sensitivity = Sensitivity.HOSTED,
    val enabled: Boolean = true,
)
