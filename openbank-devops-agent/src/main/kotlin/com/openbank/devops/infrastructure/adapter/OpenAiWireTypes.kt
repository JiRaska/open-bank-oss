// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.adapter

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Minimal wire types for the OpenAI-compatible /chat/completions endpoint (the schema DeepInfra,
 * NVIDIA NIM, Groq, vLLM all speak). Non-streaming, no tool calling — the devops-agent only needs a
 * single text completion per diagnosis/proposal. Mirrors the copilot-service wire types (ADR-0089).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    @JsonProperty("max_tokens") val maxTokens: Int = 700,
    val stream: Boolean = false,
)

data class ChatMessage(val role: String, val content: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatChoice(val message: ChatMessage = ChatMessage("assistant", ""))
