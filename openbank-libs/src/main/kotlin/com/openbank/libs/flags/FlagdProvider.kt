// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Production [FeatureClient] — evaluates against the per-service **flagd**
 * sidecar over **OFREP** (OpenFeature Remote Evaluation Protocol), ADR-0067.
 * Structurally identical to `OpaSidecarPolicyDecisionPoint`: plain
 * `java.net.http.HttpClient` so the libs JAR stays runtime-agnostic
 * (`compileOnly` everywhere — see build.gradle.kts), no Quarkus REST client.
 *
 * Configuration (resolved via `@ConfigProperty` at the service level and passed
 * to the constructor):
 *   - `openbank.flags.url`     default `http://localhost:8016`
 *   - `openbank.flags.timeout` default `PT0.1S` (sub-100 ms; flagd evaluates locally)
 *
 * ### flagd port note (verified against ghcr.io/open-feature/flagd:v0.11.4)
 * flagd serves THREE ports — `8013` gRPC evaluation, `8014` management
 * (`/healthz`, `/readyz`), `8016` OFREP HTTP. This client speaks **OFREP over
 * HTTP**, so the default targets **8016**. Pointing it at 8013 (gRPC) makes every
 * eval fail-static to the caller default — a silent, hard-to-spot outage. The
 * sidecar's k8s liveness/readiness probes must target 8014, not 8016.
 *
 * ### Fail-static, never fail-loud
 * Unlike the PDP — where an outage is a fail-*closed* deny — a flag outage is a
 * fail-*static* fallback to the caller default ([EvaluationReason.ERROR],
 * `errorCode` set). A flag system that throws on the hot path would convert a
 * sidecar blip into a customer-facing 500; the safe behaviour is "feature
 * absent". The 100 ms timeout bounds the blast radius.
 *
 * OFREP single-flag endpoint: `POST /ofrep/v1/evaluate/flags/{key}` with body
 * `{"context": { "targetingKey": ..., ...attributes }}`; response
 * `{"key","value","reason","variant","errorCode"}`.
 */
class FlagdProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val mapper: ObjectMapper = ObjectMapper(),
    httpClient: HttpClient? = null,
) : FeatureClient {
    private val http: HttpClient = httpClient ?: HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build()

    override fun boolean(flag: String, default: Boolean, ctx: EvalContext) =
        evaluate(flag, default, ctx) { it.asBoolean(default) }

    override fun string(flag: String, default: String, ctx: EvalContext) =
        evaluate(flag, default, ctx) { it.asText(default) }

    override fun integer(flag: String, default: Long, ctx: EvalContext) =
        evaluate(flag, default, ctx) { if (it.isNumber) it.asLong() else default }

    override fun double(flag: String, default: Double, ctx: EvalContext) =
        evaluate(flag, default, ctx) { if (it.isNumber) it.asDouble() else default }

    private fun <T> evaluate(flag: String, default: T, ctx: EvalContext, coerce: (JsonNode) -> T): FlagEvaluation<T> {
        val body = mapper.writeValueAsString(mapOf("context" to ctx.toInput()))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$OFREP_PATH/${enc(flag)}"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse {
                return FlagEvaluation(
                    flag,
                    default,
                    reason = EvaluationReason.ERROR,
                    errorCode = "PROVIDER_UNREACHABLE",
                )
            }

        if (response.statusCode() !in 200..299) {
            // 404 = flag not found → DEFAULT (not an error); anything else → ERROR.
            val reason = if (response.statusCode() == 404) EvaluationReason.DEFAULT else EvaluationReason.ERROR
            val code = if (reason == EvaluationReason.ERROR) "HTTP_${response.statusCode()}" else null
            return FlagEvaluation(flag, default, reason = reason, errorCode = code)
        }
        return parse(flag, default, response.body(), coerce)
    }

    /** Visible for testing — parses an OFREP evaluation response, fail-static on any defect. */
    internal fun <T> parse(flag: String, default: T, jsonBody: String, coerce: (JsonNode) -> T): FlagEvaluation<T> {
        val root = runCatching { mapper.readTree(jsonBody) }
            .getOrElse {
                return FlagEvaluation(flag, default, reason = EvaluationReason.ERROR, errorCode = "MALFORMED_RESPONSE")
            }

        val errorCode = root.path("errorCode").asText(null)
        if (errorCode != null) {
            val reason = if (errorCode == "FLAG_NOT_FOUND") EvaluationReason.DEFAULT else EvaluationReason.ERROR
            return FlagEvaluation(
                flag,
                default,
                reason = reason,
                errorCode = errorCode.takeIf {
                    reason ==
                        EvaluationReason.ERROR
                },
            )
        }

        val valueNode = root.path("value")
        if (valueNode.isMissingNode || valueNode.isNull) {
            return FlagEvaluation(flag, default, reason = EvaluationReason.DEFAULT)
        }
        return FlagEvaluation(
            flagKey = flag,
            value = coerce(valueNode),
            variant = root.path("variant").asText(null),
            reason = parseReason(root.path("reason").asText(null)),
        )
    }

    private fun EvalContext.toInput(): Map<String, Any?> = buildMap {
        targetingKey?.let { put("targetingKey", it) }
        putAll(attributes)
    }

    private companion object {
        // flagd OFREP HTTP port (8013 is gRPC, 8014 is management). See class KDoc.
        const val DEFAULT_BASE_URL = "http://localhost:8016"
        const val OFREP_PATH = "/ofrep/v1/evaluate/flags"
        val DEFAULT_TIMEOUT: Duration = Duration.ofMillis(100)

        fun enc(flag: String) = URI(null, null, flag, null).rawPath

        fun parseReason(raw: String?): EvaluationReason = when (raw?.uppercase()) {
            "STATIC" -> EvaluationReason.STATIC
            "TARGETING_MATCH" -> EvaluationReason.TARGETING_MATCH
            "SPLIT" -> EvaluationReason.SPLIT
            "DISABLED" -> EvaluationReason.DISABLED
            "DEFAULT" -> EvaluationReason.DEFAULT
            "ERROR" -> EvaluationReason.ERROR
            else -> EvaluationReason.UNKNOWN
        }
    }
}
