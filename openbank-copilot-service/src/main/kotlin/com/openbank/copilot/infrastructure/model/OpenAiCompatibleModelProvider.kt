// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.copilot.application.port.out.ModelProvider
import com.openbank.copilot.domain.model.ChatMessage
import com.openbank.copilot.domain.model.ChatRole
import com.openbank.copilot.domain.model.ModelDescriptor
import com.openbank.copilot.domain.model.ModelRequest
import com.openbank.copilot.domain.model.ModelResponse
import com.openbank.copilot.domain.model.ModelUsage
import com.openbank.copilot.domain.model.StopReason
import com.openbank.copilot.domain.model.ToolInvocation
import com.openbank.copilot.domain.model.ToolSpec
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
 * One adapter for every OpenAI-compatible chat-completions backend (ADR-0089 D6, mirrors ADR-0031).
 * The wire format is the OpenAI `/chat/completions` schema, which NVIDIA NIM, Groq, OpenRouter, vLLM,
 * Ollama and many others all speak — so a single [ModelProvider] keyed `openai-compat` serves all of
 * them: pick the backend by setting a model entry's `endpoint` (base URL) plus the API key. The
 * neutral [ModelRequest]/[ModelResponse] types (incl. tool calling) are translated here and nowhere
 * else.
 *
 * `copilot.model-gateway.models[*].id` is sent verbatim as the upstream model name (e.g.
 * `nvidia/llama-3.1-nemotron-70b-instruct`), so no registry-id ↔ vendor-name mapping is needed. The
 * API key comes from `copilot.model.api-key` (env → ESO/OpenBao in deploy) and is never logged. The
 * blocking HTTP call is intentional: the chat loop runs on a RESTEasy worker thread (runBlocking,
 * not the event loop), so a synchronous send is allowed and keeps the audit/CDI context on one thread.
 *
 * NOTE (ADR-0089 D6): a HOSTED model sends prompt context off-cluster. Acceptable in the sandbox
 * (synthetic data only); production must pin sensitive context to an in-cluster/EU model.
 */
@ApplicationScoped
class OpenAiCompatibleModelProvider : ModelProvider {

    @Inject
    lateinit var objectMapper: ObjectMapper

    // Field injection, not a constructor arg: the bean is instantiated by Arc, and detekt's
    // LongParameterList fires AT constructorThreshold rather than above it.
    @Inject
    lateinit var metrics: LlmCallMetrics

    // Resolved lazily (NOT @ConfigProperty-injected): an un-seeded/empty key would otherwise fail
    // config load at boot (SmallRye SRCFG00040 on an empty String binding) and CrashLoop the pod.
    // Empty here just degrades the call (require below) until the key is seeded.
    private val apiKey: String
        get() = ConfigProvider.getConfig()
            .getOptionalValue("copilot.model.api-key", String::class.java).orElse("")

    override val key: String = "openai-compat"

    private val log = Logger.getLogger(OpenAiCompatibleModelProvider::class.java)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S)).build()
    }

    override suspend fun complete(model: ModelDescriptor, request: ModelRequest): ModelResponse {
        val base = (model.endpoint ?: error("model '${model.id}' has no endpoint (base URL) configured"))
            .trimEnd('/')
        if (apiKey.isBlank()) {
            metrics.recordCall(model.id, LlmCallMetricsPort.OUTCOME_NOT_CONFIGURED, 0, 0, 0)
        }
        require(apiKey.isNotBlank()) {
            "copilot.model.api-key is empty — set the backend API key to use model '${model.id}'"
        }
        val url = "$base/chat/completions"
        val payload = objectMapper.writeValueAsString(buildRequestBody(model, request))

        val httpRequest = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        // Timed around send AND parse: `usage` is only known after parsing, and a 200 whose body
        // does not parse is a failed call from the caller's point of view.
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
            )
            throw ex
        }
        if (resp.statusCode() !in OK_RANGE) {
            // Body may echo the request on some gateways; do not log it wholesale (prompt PII).
            log.warnf("openai-compat backend %s returned HTTP %d for model %s", base, resp.statusCode(), model.id)
            metrics.recordCall(
                model.id,
                LlmCallMetricsPort.OUTCOME_HTTP_ERROR,
                0,
                0,
                System.nanoTime() - startedAt,
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
        )
        return parsed
    }

    /**
     * SSE streaming completion (stream: true). For text responses, [onChunk] is called with each
     * text delta as it arrives from the NIM backend — the first token shows within ~500 ms instead
     * of the full generation time. For tool-call rounds the model emits no text, so [onChunk] is
     * never called; tool invocations are accumulated from incremental delta chunks and returned in
     * the [ModelResponse]. Runs on the caller's thread (blocking InputStream reads are deliberate —
     * this method is always called from a worker thread via runBlocking, ADR-0089).
     */
    override suspend fun completeStream(
        model: ModelDescriptor,
        request: ModelRequest,
        onChunk: suspend (String) -> Unit,
    ): ModelResponse {
        val base = (model.endpoint ?: error("model '${model.id}' has no endpoint (base URL) configured"))
            .trimEnd('/')
        require(apiKey.isNotBlank()) {
            "copilot.model.api-key is empty — set the backend API key to use model '${model.id}'"
        }
        val payload = objectMapper.writeValueAsString(buildRequestBody(model, request).apply { put("stream", true) })
        val httpRequest = HttpRequest.newBuilder(URI.create("$base/chat/completions"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        // BodyHandlers.ofInputStream() returns as soon as headers arrive; body is streamed on demand.
        val resp = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() !in OK_RANGE) {
            log.warnf(
                "openai-compat backend %s returned HTTP %d (stream) for model %s",
                base,
                resp.statusCode(),
                model.id,
            )
            error("model backend HTTP ${resp.statusCode()} for '${model.id}'")
        }

        val acc = StreamAccumulator(model.id)
        resp.body().bufferedReader().use { reader ->
            for (line in reader.lineSequence()) {
                val data = line.removePrefix("data: ").trimEnd()
                when {
                    data == "[DONE]" -> break
                    data.isNotBlank() -> processDataLine(data, acc, onChunk)
                }
            }
        }

        val invocations = buildInvocations(acc)
        return ModelResponse(
            content = acc.contentBuf.toString(),
            toolInvocations = invocations,
            stopReason = stopReasonFromFinish(acc.finishReason, invocations.isNotEmpty()),
            usage = ModelUsage(inputTokens = acc.inputTokens, outputTokens = acc.outputTokens),
            modelId = model.id,
            modelVersion = acc.modelVersion,
        )
    }

    /** Mutable accumulation state for one SSE stream. */
    private class StreamAccumulator(defaultModelId: String) {
        val contentBuf = StringBuilder()
        var finishReason = "stop"
        var modelVersion = defaultModelId
        var inputTokens = 0
        var outputTokens = 0
        val toolIds = mutableMapOf<Int, String>()
        val toolNames = mutableMapOf<Int, String>()
        val toolArgBufs = mutableMapOf<Int, StringBuilder>()
    }

    /** Process one non-empty, non-[DONE] SSE data payload into [acc], emitting text to [onChunk]. */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private suspend fun processDataLine(data: String, acc: StreamAccumulator, onChunk: suspend (String) -> Unit) {
        val chunk = runCatching { objectMapper.readTree(data) }.getOrNull() ?: return
        val choice = chunk.path("choices").firstOrNull() ?: return
        val delta = choice.path("delta")

        choice.path("finish_reason").takeUnless { it.isMissingNode || it.isNull }
            ?.asText()?.let { acc.finishReason = it }
        chunk.path("model").takeUnless { it.isMissingNode || it.isNull }
            ?.asText()?.takeIf { it.isNotBlank() }?.let { acc.modelVersion = it }

        // Text content — emit to caller immediately.
        delta.path("content").takeUnless { it.isMissingNode || it.isNull }
            ?.asText()?.takeIf { it.isNotEmpty() }?.let { text ->
                acc.contentBuf.append(text)
                onChunk(text)
            }

        // Tool call deltas — accumulate by index; name/args arrive incrementally.
        delta.path("tool_calls").takeIf { it.isArray }?.forEach { tc ->
            val idx = tc.path("index").asInt(0)
            tc.path("id").takeUnless { it.isMissingNode }?.asText()?.let { acc.toolIds[idx] = it }
            val fn = tc.path("function")
            fn.path("name").takeUnless { it.isMissingNode }?.asText()?.let { acc.toolNames[idx] = it }
            fn.path("arguments").takeUnless { it.isMissingNode }?.asText()
                ?.let { acc.toolArgBufs.getOrPut(idx) { StringBuilder() }.append(it) }
        }

        // Usage appears in the last data chunk before [DONE] on some backends.
        chunk.path("usage").takeUnless { it.isMissingNode }?.let { u ->
            acc.inputTokens = u.path("prompt_tokens").asInt(0)
            acc.outputTokens = u.path("completion_tokens").asInt(0)
        }
    }

    private fun buildInvocations(acc: StreamAccumulator): List<ToolInvocation> =
        acc.toolArgBufs.keys.sorted().mapNotNull { idx ->
            val name = acc.toolNames[idx] ?: return@mapNotNull null
            val argsRaw = acc.toolArgBufs[idx]?.toString() ?: "{}"
            val args = runCatching { objectMapper.readTree(argsRaw) }.getOrElse { objectMapper.createObjectNode() }
            ToolInvocation(id = acc.toolIds[idx] ?: "", name = name, arguments = args)
        }

    private fun stopReasonFromFinish(finishReason: String, hasInvocations: Boolean): StopReason = when (finishReason) {
        "tool_calls" -> StopReason.TOOL_USE
        "length" -> StopReason.MAX_TOKENS
        "content_filter" -> StopReason.FILTERED
        else -> if (hasInvocations) StopReason.TOOL_USE else StopReason.END
    }

    /** Neutral [ModelRequest] -> OpenAI `/chat/completions` request body. */
    internal fun buildRequestBody(model: ModelDescriptor, request: ModelRequest): ObjectNode {
        val root = objectMapper.createObjectNode()
        root.put("model", model.id)
        root.put("max_tokens", request.maxTokens)
        root.put("temperature", request.temperature)

        val messages = root.putArray("messages")
        for (m in request.messages) appendMessage(messages, m)

        if (request.tools.isNotEmpty()) appendTools(root, request.tools)
        return root
    }

    private fun appendMessage(messages: ArrayNode, m: ChatMessage) {
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
            ChatRole.ASSISTANT -> appendAssistant(msg, m)
        }
    }

    private fun appendAssistant(msg: ObjectNode, m: ChatMessage) {
        msg.put("role", "assistant")
        // content may be null when the turn is purely tool calls (OpenAI spec).
        if (m.content.isNotBlank()) msg.put("content", m.content) else msg.putNull("content")
        if (m.toolCalls.isEmpty()) return
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

    private fun appendTools(root: ObjectNode, tools: List<ToolSpec>) {
        val arr = root.putArray("tools")
        for (t in tools) {
            val tool = arr.addObject()
            tool.put("type", "function")
            val fn = tool.putObject("function")
            fn.put("name", t.name)
            fn.put("description", t.description)
            fn.set<JsonNode>("parameters", objectMapper.valueToTree(t.inputSchema))
        }
        root.put("tool_choice", "auto")
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
        val stopReason = stopReasonFromFinish(finish, invocations.isNotEmpty())

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

    private companion object {
        const val CONNECT_TIMEOUT_S = 10L
        const val REQUEST_TIMEOUT_S = 60L
        val OK_RANGE = 200..299
    }
}
