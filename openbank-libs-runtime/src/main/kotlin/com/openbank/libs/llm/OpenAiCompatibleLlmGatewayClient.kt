// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
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
 * The shared OpenAI-compatible implementation of [LlmGatewayPort] (ADR-0174 / ADR-0175).
 *
 * Consolidates the four hand-rolled `/chat/completions` clients (agent-service, copilot-service,
 * devops-agent, control-liveness-sentinel) into one. It is the single place the fleet's LLM egress
 * flows through, so:
 *  - repointing every agent from `api.deepinfra.com` to the in-cluster gateway
 *    (`http://litellm.ai-platform.svc:4000`) is one [baseUrl] config change (ADR-0174);
 *  - an egress NetworkPolicy that allows only the gateway to leave the cluster now has a single
 *    choke point to sit in front of (ADR-0175 §4).
 *
 * Safety, matching the existing adapters:
 *  - an empty [apiKey] degrades the call to `null` (deterministic fallback) rather than throwing —
 *    the SmallRye SRCFG00040 CrashLoop footgun the adapters avoid with an optional key lookup;
 *  - the key is never logged;
 *  - the caller owns prompt construction and the ADR-0031 untrusted-input fencing — this client only
 *    transports; it adds no instructions of its own.
 *
 * Framework-touching (HttpClient, Jackson, coroutine dispatch), so it lives in `openbank-libs-runtime`
 * per the ADR-0122 domain/runtime split; the port stays pure in `openbank-libs-domain`. Constructor
 * args are injectable so a unit test can point [baseUrl] at a local stub and swap [http].
 */
class OpenAiCompatibleLlmGatewayClient(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
    // jacksonObjectMapper(): the plain ObjectMapper() cannot instantiate the Kotlin data-class wire
    // types (no default ctor) and silently fails deserialization to null — the Kotlin module is required.
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    http: HttpClient? = null,
    private val temperature: Double = 0.2,
    private val maxTokens: Int = 700,
    // Defaults to the no-op so an un-wired caller stays silent rather than broken, and so this
    // stays a plain constructible class — `DomainMetrics` is the CDI bean that implements it, and
    // producing one from here would drag Micrometer into every consumer's Arc type closure.
    private val metrics: LlmCallMetricsPort = LlmCallMetricsPort.NONE,
) : LlmGatewayPort {

    private val log = Logger.getLogger(OpenAiCompatibleLlmGatewayClient::class.java)

    private val http: HttpClient = http ?: HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S))
        .build()

    @Suppress("TooGenericExceptionCaught") // IOException + parse errors have no common base worth splitting
    override suspend fun chat(systemPrompt: String, userPrompt: String): String? {
        if (apiKey.isBlank()) {
            log.warn("LLM api-key not seeded — skipping call (degraded to deterministic fallback)")
            // Recorded, not silent: an agent that has never had a key looks identical to one that
            // is simply idle unless this series exists, and "the AI features were never switched
            // on" is exactly the state this repo keeps discovering months late.
            metrics.recordCall(model, LlmCallMetricsPort.OUTCOME_NOT_CONFIGURED, 0, 0, 0)
            return null
        }
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val body = ChatRequest(
            model = model,
            messages = listOf(ChatMessage("system", systemPrompt), ChatMessage("user", userPrompt)),
            temperature = temperature,
            maxTokens = maxTokens,
        )
        // Nanos, not a Timer.Sample: the metrics port is a pure-domain interface and may not carry a
        // Micrometer type. Measured around the whole attempt, so a timeout — the slowest and most
        // interesting case — is timed too rather than being dropped with the exception.
        val startedAt = System.nanoTime()
        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build()
            // Blocking send off the event loop.
            val resp = withContext(Dispatchers.IO) {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            }
            if (resp.statusCode() !in OK_RANGE) {
                log.warnf("LLM backend %s returned HTTP %d", baseUrl, resp.statusCode())
                metrics.recordCall(
                    model,
                    LlmCallMetricsPort.OUTCOME_HTTP_ERROR,
                    0,
                    0,
                    System.nanoTime() - startedAt,
                )
                return null
            }
            val parsed = mapper.readValue(resp.body(), ChatResponse::class.java)
            metrics.recordCall(
                model,
                LlmCallMetricsPort.OUTCOME_SUCCESS,
                parsed.usage?.promptTokens ?: 0,
                parsed.usage?.completionTokens ?: 0,
                System.nanoTime() - startedAt,
            )
            parsed.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
        } catch (ex: Exception) {
            log.warnf("LLM call failed: %s", ex.message)
            metrics.recordCall(
                model,
                LlmCallMetricsPort.OUTCOME_EXCEPTION,
                0,
                0,
                System.nanoTime() - startedAt,
            )
            null
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 10L
        const val REQUEST_TIMEOUT_S = 60L
        val OK_RANGE = 200..299
    }
}

// --- OpenAI-compatible wire types (the schema DeepInfra, NVIDIA NIM, Groq, vLLM, LiteLLM speak) ---

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    @JsonProperty("max_tokens") val maxTokens: Int = 700,
    val stream: Boolean = false,
)

internal data class ChatMessage(val role: String, val content: String)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
    // Every OpenAI-compatible backend returns this block on a successful non-streaming call, but
    // it is nullable because none of them PROMISE to: the client must not start returning null
    // completions because a provider dropped `usage`. Until now it was parsed away by
    // @JsonIgnoreProperties, which is why the fleet's token consumption existed only on the
    // provider's invoice.
    val usage: ChatUsage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ChatUsage(
    @JsonProperty("prompt_tokens") val promptTokens: Int = 0,
    @JsonProperty("completion_tokens") val completionTokens: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ChatChoice(val message: ChatMessage = ChatMessage("assistant", ""))
