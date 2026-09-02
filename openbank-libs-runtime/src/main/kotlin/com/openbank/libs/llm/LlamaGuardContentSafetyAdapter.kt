// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.llm

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [ContentSafetyPort] backed by a Llama Guard model served through the in-cluster LiteLLM gateway
 * (ADR-0174 / ADR-0175) — the `llama-guard` half of the ADR-0031 guardrails declaration, which up
 * to now was declared and not deployed.
 *
 * ## Why this is not [OpenAiCompatibleLlmGatewayClient] with a different model name
 *
 * Llama Guard is not a chat model with a safety prompt: the provider applies its own template to
 * the `messages` array, and the ROLE of the last message decides what is being judged — a `user`
 * message is judged as input, an `assistant` message as a completion. The shared gateway client
 * always sends `system` + `user`, which would classify an assistant completion as if the customer
 * had said it, and quietly return the wrong verdict. So this adapter owns its own request shape.
 * It deliberately sends **no system prompt**: injecting instructions into a classifier's context is
 * the attack it exists to catch.
 *
 * ## Reading the answer
 *
 * The model replies with `safe`, or `unsafe` followed by newline-separated hazard codes (`S2`, `S9`,
 * …). Anything else — an empty body, a refusal, a chatty paragraph — is
 * [ContentSafetyPort.Decision.UNAVAILABLE] with reason `unparseable`, never an optimistic `SAFE`.
 * A blank [apiKey] degrades to `not_configured` rather than throwing, matching the fleet's
 * documented degraded mode (SRCFG00040 CrashLoop avoidance), and is still counted so that "the
 * guardrail was never switched on" is a visible series and not silence.
 */
class LlamaGuardContentSafetyAdapter(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    http: HttpClient? = null,
    private val metrics: ContentSafetyMetricsPort = ContentSafetyMetricsPort.NONE,
    private val callMetrics: LlmCallMetricsPort = LlmCallMetricsPort.NONE,
    /**
     * Hard cap on the text handed to the classifier. A guardrail must not become the slowest hop in
     * the request, and Llama Guard's verdict on a truncated prefix is still a verdict; the
     * deterministic `PromptInjectionGuard` sees the whole string regardless.
     */
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) : ContentSafetyPort {

    private val log = Logger.getLogger(LlamaGuardContentSafetyAdapter::class.java)

    private val http: HttpClient = http ?: HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S))
        .build()

    @Suppress("TooGenericExceptionCaught") // IOException + parse errors share no base worth splitting
    override suspend fun classify(role: ContentSafetyPort.SafetyRole, text: String): ContentSafetyPort.SafetyVerdict {
        if (apiKey.isBlank()) {
            return unavailable(role, ContentSafetyPort.REASON_NOT_CONFIGURED, notConfigured = true)
        }
        val startedAt = System.nanoTime()
        return try {
            val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/chat/completions"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            ChatRequest(
                                model = model,
                                messages = listOf(ChatMessage(wireRole(role), text.take(maxChars))),
                                temperature = 0.0,
                                maxTokens = MAX_VERDICT_TOKENS,
                            ),
                        ),
                    ),
                )
                .build()
            val resp = withContext(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            if (resp.statusCode() !in OK_RANGE) {
                log.warnf("content-safety backend returned HTTP %d", resp.statusCode())
                callMetrics.recordCall(
                    model,
                    LlmCallMetricsPort.OUTCOME_HTTP_ERROR,
                    0,
                    0,
                    System.nanoTime() - startedAt,
                )
                return unavailable(role, ContentSafetyPort.REASON_TRANSPORT)
            }
            val parsed = mapper.readValue(resp.body(), ChatResponse::class.java)
            callMetrics.recordCall(
                model,
                LlmCallMetricsPort.OUTCOME_SUCCESS,
                parsed.usage?.promptTokens ?: 0,
                parsed.usage?.completionTokens ?: 0,
                System.nanoTime() - startedAt,
            )
            interpret(role, parsed.choices.firstOrNull()?.message?.content)
        } catch (ex: Exception) {
            log.warnf("content-safety call failed: %s", ex.message)
            callMetrics.recordCall(model, LlmCallMetricsPort.OUTCOME_EXCEPTION, 0, 0, System.nanoTime() - startedAt)
            unavailable(role, ContentSafetyPort.REASON_TRANSPORT)
        }
    }

    private fun interpret(role: ContentSafetyPort.SafetyRole, content: String?): ContentSafetyPort.SafetyVerdict {
        val lines = content?.trim()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val head = lines.firstOrNull()?.lowercase()
        return when {
            head == "safe" -> record(
                role,
                ContentSafetyPort.SafetyVerdict(ContentSafetyPort.Decision.SAFE, model = model),
            )
            head == "unsafe" -> record(
                role,
                ContentSafetyPort.SafetyVerdict(
                    decision = ContentSafetyPort.Decision.UNSAFE,
                    // Codes arrive either on the following line ("S2, S9") or, on some providers,
                    // comma-separated on that same line. Both are split the same way.
                    categories = lines.drop(1)
                        .flatMap { it.split(',') }
                        .map { it.trim().uppercase() }
                        .filter { it.matches(HAZARD_CODE) },
                    model = model,
                ),
            )
            else -> {
                log.warnf("content-safety verdict unparseable (first line=%s)", head ?: "<empty>")
                unavailable(role, ContentSafetyPort.REASON_UNPARSEABLE)
            }
        }
    }

    private fun unavailable(
        role: ContentSafetyPort.SafetyRole,
        reason: String,
        notConfigured: Boolean = false,
    ): ContentSafetyPort.SafetyVerdict {
        if (notConfigured) {
            callMetrics.recordCall(model, LlmCallMetricsPort.OUTCOME_NOT_CONFIGURED, 0, 0, 0)
        }
        return record(
            role,
            ContentSafetyPort.SafetyVerdict(ContentSafetyPort.Decision.UNAVAILABLE, model = model, reason = reason),
        )
    }

    private fun record(
        role: ContentSafetyPort.SafetyRole,
        verdict: ContentSafetyPort.SafetyVerdict,
    ): ContentSafetyPort.SafetyVerdict {
        // `blocked` is reported false here and re-reported by the caller that applies its own
        // fail-closed policy: this adapter does not know the caller's risk posture, and guessing it
        // would put a wrong label on the one series an outage alert reads.
        metrics.recordClassification(model, wireRole(role), verdict.decision.name.lowercase(), blocked = false)
        return verdict
    }

    private fun wireRole(role: ContentSafetyPort.SafetyRole): String = when (role) {
        ContentSafetyPort.SafetyRole.USER -> "user"
        ContentSafetyPort.SafetyRole.ASSISTANT -> "assistant"
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 5L

        /**
         * Deliberately far below the gateway's 60 s chat timeout: a guardrail that adds a minute to
         * a blocked request is a denial-of-service on the surface it protects, and an unreachable
         * classifier should reach the caller's fail-closed decision fast.
         */
        const val REQUEST_TIMEOUT_S = 10L
        const val MAX_VERDICT_TOKENS = 24
        const val DEFAULT_MAX_CHARS = 6000
        val OK_RANGE = 200..299
        val HAZARD_CODE = Regex("^S\\d{1,2}$")
    }
}
