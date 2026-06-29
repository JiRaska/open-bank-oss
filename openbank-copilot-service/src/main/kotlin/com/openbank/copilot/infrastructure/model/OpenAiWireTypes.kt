// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.infrastructure.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/** Wire types for OpenAI-compatible chat completions endpoint (/v1/chat/completions). */

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiTool>? = null,
    val temperature: Double = 0.2,
    @JsonProperty("top_p") val topP: Double = 0.7,
    @JsonProperty("max_tokens") val maxTokens: Int = 1024,
    val stream: Boolean = false,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    @JsonProperty("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
    @JsonProperty("tool_call_id") val toolCallId: String? = null,
)

data class OpenAiTool(val type: String = "function", val function: OpenAiFunctionDef)

data class OpenAiFunctionDef(val name: String, val description: String, val parameters: Map<String, Any>)

data class OpenAiChatResponse(
    val id: String = "",
    val model: String = "",
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

data class OpenAiChoice(
    val message: OpenAiMessage = OpenAiMessage("assistant"),
    @JsonProperty("finish_reason") val finishReason: String? = null,
)

data class OpenAiUsage(
    @JsonProperty("prompt_tokens") val promptTokens: Int = 0,
    @JsonProperty("completion_tokens") val completionTokens: Int = 0,
)

data class OpenAiToolCall(val id: String, val type: String = "function", val function: OpenAiToolCallFunction)

data class OpenAiToolCallFunction(val name: String, val arguments: String)
