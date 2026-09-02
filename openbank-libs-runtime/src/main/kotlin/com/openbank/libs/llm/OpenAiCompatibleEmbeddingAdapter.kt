// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
 * [EmbeddingPort] over the OpenAI-compatible `/embeddings` endpoint the LiteLLM gateway serves
 * (ADR-0174 / ADR-0175, ADR-0183 §3).
 *
 * Two things it refuses to paper over, because both would corrupt an index silently rather than
 * fail it:
 *  - **order.** The API is documented to return an `index` per item, and providers do reorder under
 *    batching. The vectors are sorted by that index before being returned, so `embed(texts)[i]` is
 *    the embedding of `texts[i]` — a mis-paired index is invisible at write time and shows up much
 *    later as "search returns unrelated passages".
 *  - **width.** A vector whose length is not [dimensions] is rejected for the whole batch. The
 *    database column is `vector(N)`; a wrong width otherwise fails one INSERT at a time with a
 *    cast error, which reads as a database problem rather than a model swap.
 */
class OpenAiCompatibleEmbeddingAdapter(
    private val baseUrl: String,
    override val model: String,
    override val dimensions: Int,
    private val apiKey: String,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    http: HttpClient? = null,
    private val metrics: LlmCallMetricsPort = LlmCallMetricsPort.NONE,
) : EmbeddingPort {

    private val log = Logger.getLogger(OpenAiCompatibleEmbeddingAdapter::class.java)

    private val http: HttpClient = http ?: HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S))
        .build()

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override suspend fun embed(texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        if (apiKey.isBlank()) {
            log.warn("embedding api-key not seeded — retrieval degrades to keyword-only")
            metrics.recordCall(model, LlmCallMetricsPort.OUTCOME_NOT_CONFIGURED, 0, 0, 0)
            return null
        }
        val startedAt = System.nanoTime()
        return try {
            val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/embeddings"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(EmbeddingRequest(model = model, input = texts)),
                    ),
                )
                .build()
            val resp = withContext(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            if (resp.statusCode() !in OK_RANGE) {
                log.warnf("embedding backend returned HTTP %d", resp.statusCode())
                metrics.recordCall(model, LlmCallMetricsPort.OUTCOME_HTTP_ERROR, 0, 0, System.nanoTime() - startedAt)
                return null
            }
            val parsed = mapper.readValue(resp.body(), EmbeddingResponse::class.java)
            metrics.recordCall(
                model,
                LlmCallMetricsPort.OUTCOME_SUCCESS,
                parsed.usage?.promptTokens ?: 0,
                0,
                System.nanoTime() - startedAt,
            )
            validate(parsed, texts.size)
        } catch (ex: Exception) {
            log.warnf("embedding call failed: %s", ex.message)
            metrics.recordCall(model, LlmCallMetricsPort.OUTCOME_EXCEPTION, 0, 0, System.nanoTime() - startedAt)
            null
        }
    }

    private fun validate(parsed: EmbeddingResponse, expected: Int): List<FloatArray>? {
        if (parsed.data.size != expected) {
            log.warnf("embedding response has %d vectors for %d inputs — discarding", parsed.data.size, expected)
            return null
        }
        val ordered = parsed.data.sortedBy { it.index }.map { it.embedding }
        val wrongWidth = ordered.firstOrNull { it.size != dimensions }
        if (wrongWidth != null) {
            log.errorf(
                "embedding width %d != declared %d for model %s — the vector column would reject it; " +
                    "discarding the batch",
                wrongWidth.size,
                dimensions,
                model,
            )
            return null
        }
        return ordered.map { it.toFloatArray() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 5L

        /** Batches of corpus chunks are slower than a chat turn; still bounded well under a request. */
        const val REQUEST_TIMEOUT_S = 30L
        val OK_RANGE = 200..299
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class EmbeddingRequest(val model: String, val input: List<String>)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class EmbeddingResponse(val data: List<EmbeddingDatum> = emptyList(), val usage: EmbeddingUsage? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class EmbeddingDatum(
    // Defaults to 0 rather than being required: a provider that omits `index` returns items in
    // request order, and a stable sort then leaves that order untouched.
    val index: Int = 0,
    val embedding: List<Float> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class EmbeddingUsage(@JsonProperty("prompt_tokens") val promptTokens: Int = 0)
