// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.agent.application.port.out.ModelProvider
import com.openbank.agent.domain.model.ChatRole
import com.openbank.agent.domain.model.ModelDescriptor
import com.openbank.agent.domain.model.ModelRequest
import com.openbank.agent.domain.model.ModelResponse
import com.openbank.agent.domain.model.ModelUsage
import com.openbank.agent.domain.model.StopReason
import com.openbank.agent.domain.model.ToolInvocation
import com.openbank.libs.llm.LlmCallMetricsPort
import com.openbank.libs.observability.LlmCallMetrics
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One adapter for every OpenAI-compatible chat-completions backend (ADR-0031 D6). The wire format
 * is the OpenAI `/chat/completions` schema, which Groq, OpenRouter, Together, vLLM, Ollama and
 * many others all speak — so a single [ModelProvider] keyed `openai-compat` serves all of them:
 * pick the backend by setting a model entry's `endpoint` (base URL) plus the API key. The neutral
 * [ModelRequest]/[ModelResponse] types (incl. tool calling) are translated here and nowhere else.
 *
 * `model-gateway.models[*].id` is sent verbatim as the upstream model name (e.g.
 * `llama-3.3-70b-versatile`), so no registry-id ↔ vendor-name mapping is needed. The API key comes
 * from `agent.model.openai.api-key` (wired to an env var in application.yaml; Vault in prod) and is
 * never logged. The blocking HTTP call is intentional: the chat path runs on a RESTEasy worker
 * thread (not the event loop), so a synchronous send is allowed and keeps the audit/CDI context on
 * one thread (see ChatEndpoint).
 */
@ApplicationScoped
class OpenAiCompatibleModelProvider : ModelProvider {

    @Inject
    lateinit var objectMapper: ObjectMapper

    // Field injection, not a constructor arg: detekt's LongParameterList fires AT
    // constructorThreshold, and this class is instantiated by Arc, so there is no
    // constructor to widen anyway. Same shape as LoanStageEventConsumer / VopRateLimitFilter.
    @Inject
    lateinit var metrics: LlmCallMetrics

    // Resolved lazily (NOT @ConfigProperty-injected): an un-seeded/empty key would otherwise fail
    // config load at boot (SmallRye SRCFG00040 on an empty String binding) and CrashLoop the pod —
    // and break any @QuarkusTest / fresh env where GROQ_API_KEY isn't set. Empty here just degrades
    // the call (the require() below) until the key is seeded. Mirrors copilot-service (#1084).
    private val apiKey: String
        get() = ConfigProvider.getConfig()
            .getOptionalValue("agent.model.openai.api-key", String::class.java).orElse("")

    override val key: String = "openai-compat"

    private val log = Logger.getLogger(OpenAiCompatibleModelProvider::class.java)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    override suspend fun complete(model: ModelDescriptor, request: ModelRequest): ModelResponse {
        val base = (model.endpoint ?: error("model '${model.id}' has no endpoint (base URL) configured"))
            .trimEnd('/')
        // Which egress backend this attempt was addressed to (#5736). Derived from the endpoint the
        // model descriptor carries, so it follows a repoint (direct provider -> LiteLLM gateway)
        // without a second config knob to keep in step.
        val provider = LlmCallMetricsPort.providerOf(base)
        if (apiKey.isBlank()) {
            metrics.recordCall(model.id, LlmCallMetricsPort.OUTCOME_NOT_CONFIGURED, 0, 0, 0, provider)
        }
        require(apiKey.isNotBlank()) {
            "agent.model.openai.api-key is empty — set the backend API key (e.g. GROQ_API_KEY) to use model '${model.id}'"
        }
        val url = "$base/chat/completions"
        val payload = objectMapper.writeValueAsString(buildRequestBody(model, request))

        val httpRequest = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        // Timed around the send AND the parse: `usage` is only known after parsing, and a body that
        // fails to parse is a failed call from the caller's point of view even though HTTP said 200.
        val startedAt = System.nanoTime()
        val resp = try {
            http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (ex: java.io.IOException) {
            metrics.recordCall(
                model.id,
                LlmCallMetricsPort.OUTCOME_EXCEPTION,
                0,
                0,
                System.nanoTime() - startedAt,
                provider,
            )
            throw ex
        }
        if (resp.statusCode() !in 200..299) {
            // Body may echo the request on some gateways; do not log it wholesale (prompt PII).
            log.warnf("openai-compat backend %s returned HTTP %d for model %s", base, resp.statusCode(), model.id)
            metrics.recordCall(
                model.id,
                LlmCallMetricsPort.OUTCOME_HTTP_ERROR,
                0,
                0,
                System.nanoTime() - startedAt,
                provider,
            )
            error("model backend HTTP ${resp.statusCode()} for '${model.id}'")
        }
        val parsed = parseResponse(model, resp.body())
        metrics.recordCall(
            model.id,
            LlmCallMetricsPort.OUTCOME_SUCCESS,
            parsed.usage.inputTokens,
            parsed.usage.outputTokens,
            System.nanoTime() - startedAt,
            provider,
        )
        return parsed
    }

    /** Neutral [ModelRequest] -> OpenAI `/chat/completions` request body. */
    internal fun buildRequestBody(model: ModelDescriptor, request: ModelRequest): ObjectNode {
        val root = objectMapper.createObjectNode()
        root.put("model", model.id)
        root.put("max_tokens", request.maxTokens)
        root.put("temperature", request.temperature)

        val messages = root.putArray("messages")
        for (m in request.messages) {
            val msg = messages.addObject()
            when (m.role) {
                ChatRole.SYSTEM -> {
                    msg.put("role", "system")
                    msg.put("content", m.content)
                }
                ChatRole.USER -> {
                    msg.put("role", "user")
                    msg.put("content", m.content)
                }
                ChatRole.TOOL -> {
                    msg.put("role", "tool")
                    msg.put("tool_call_id", m.toolCallId ?: "")
                    msg.put("content", m.content)
                }
                ChatRole.ASSISTANT -> {
                    msg.put("role", "assistant")
                    // content may be null when the turn is purely tool calls (OpenAI spec).
                    if (m.content.isNotBlank()) msg.put("content", m.content) else msg.putNull("content")
                    if (m.toolCalls.isNotEmpty()) {
                        val calls = msg.putArray("tool_calls")
                        for (inv in m.toolCalls) {
                            val call = calls.addObject()
                            call.put("id", inv.id)
                            call.put("type", "function")
                            val fn = call.putObject("function")
                            fn.put("name", inv.name)
                            // OpenAI wants arguments as a JSON *string*, not an object.
                            fn.put("arguments", objectMapper.writeValueAsString(inv.arguments))
                        }
                    }
                }
            }
        }

        if (request.tools.isNotEmpty()) {
            val tools = root.putArray("tools")
            for (t in request.tools) {
                val tool = tools.addObject()
                tool.put("type", "function")
                val fn = tool.putObject("function")
                fn.put("name", t.name)
                fn.put("description", t.description)
                fn.set<JsonNode>("parameters", objectMapper.valueToTree(t.inputSchema))
            }
            root.put("tool_choice", "auto")
        }
        return root
    }

    /** OpenAI `/chat/completions` response -> neutral [ModelResponse]. */
    internal fun parseResponse(model: ModelDescriptor, json: String): ModelResponse {
        val root = objectMapper.readTree(json)
        val choice = root.path("choices").firstOrNull()
            ?: error("model backend returned no choices for '${model.id}'")
        val message = choice.path("message")
        val content = message.path("content").let { if (it.isNull || it.isMissingNode) "" else it.asText() }

        val toolCallsNode = message.path("tool_calls")
        val invocations = if (toolCallsNode is ArrayNode) {
            toolCallsNode.map { tc ->
                val fn = tc.path("function")
                val argsRaw = fn.path("arguments").asText("{}")
                val args = runCatching { objectMapper.readTree(argsRaw) }
                    .getOrElse { objectMapper.createObjectNode() }
                ToolInvocation(
                    id = tc.path("id").asText(""),
                    name = fn.path("name").asText(""),
                    arguments = args,
                )
            }
        } else {
            emptyList()
        }

        val finish = choice.path("finish_reason").asText("stop")
        val stopReason = when (finish) {
            "tool_calls" -> StopReason.TOOL_USE
            "length" -> StopReason.MAX_TOKENS
            "content_filter" -> StopReason.FILTERED
            else -> if (invocations.isNotEmpty()) StopReason.TOOL_USE else StopReason.END
        }

        val usage = root.path("usage")
        return ModelResponse(
            content = content,
            toolInvocations = invocations,
            stopReason = stopReason,
            usage = ModelUsage(
                inputTokens = usage.path("prompt_tokens").asInt(0),
                outputTokens = usage.path("completion_tokens").asInt(0),
            ),
            modelId = model.id,
            modelVersion = root.path("model").asText(model.id),
        )
    }
}
