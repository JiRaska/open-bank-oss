// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.llm

/**
 * The single seam every agent/copilot uses to reach an LLM (ADR-0174 / ADR-0175).
 *
 * Today four services hand-roll their own `java.net.http.HttpClient` against an OpenAI-compatible
 * `/chat/completions` endpoint (agent-service, copilot-service, devops-agent,
 * control-liveness-sentinel), each with a slightly different provider URL — and the six stub agents
 * point at a `litellm.ai-platform:4000` gateway that has never been deployed (ADR-0174 §2). That
 * makes the US-provider egress un-enforced (ADR-0175 §4): there is no single choke point to route
 * through an in-cluster gateway or to bound with an egress NetworkPolicy.
 *
 * This port is that choke point. A pure-domain interface (no framework imports): the caller builds
 * its own prompt (including the ADR-0031 untrusted-input fencing) and gets back the completion text,
 * or `null` when the backend is unreachable / unconfigured — so every caller degrades to a
 * deterministic path exactly as the hand-rolled adapters do today. The runtime implementation
 * (`OpenAiCompatibleLlmGatewayClient`) owns the HTTP/JSON and the base-URL config, so repointing the
 * whole fleet from `api.deepinfra.com` to the in-cluster gateway is one config change, not N.
 */
interface LlmGatewayPort {
    /**
     * One chat round: a system prompt + a user prompt in, the assistant's text out.
     * Returns `null` on any failure (unconfigured key, unreachable backend, non-2xx, empty choice) —
     * the caller must treat `null` as "no model available" and fall back deterministically.
     */
    suspend fun chat(systemPrompt: String, userPrompt: String): String?
}
