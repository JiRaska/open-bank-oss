// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
