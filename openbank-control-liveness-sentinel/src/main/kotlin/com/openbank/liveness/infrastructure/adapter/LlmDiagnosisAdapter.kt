// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.liveness.application.port.out.LlmDiagnosisPort
import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.infrastructure.config.LivenessSentinelConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.ConfigProvider
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Real OpenAI-compatible `/chat/completions` client (ADR-0163), same wire shape and provider as
 * devops-agent's `DevOpsConfig`/adapter (ADR-0119) and the customer copilot (ADR-0089) — NOT the
 * "litellm.ai-platform" gateway every sibling agent's original charter/stub named, which is not
 * actually deployed anywhere in this repo (verified: no LiteLLM Deployment/Service manifest
 * exists). The API key is an OPTIONAL lookup so an un-seeded key degrades diagnosis to a
 * placeholder instead of CrashLooping the pod at boot (SmallRye SRCFG00040 on an empty bind).
 *
 * `proposeFixDiff` deliberately stays unimplemented (always null): generating a code/IaC diff
 * for an unreviewed auto-apply is a materially bigger, riskier lift than a text diagnosis, and
 * ADR-0163's own design already treats "propose a tracking ticket" as the expected fallback when
 * no diff is available — this is that fallback, not a stub standing in for a real feature.
 */
@ApplicationScoped
class LlmDiagnosisAdapter(private val config: LivenessSentinelConfig) : LlmDiagnosisPort {

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(LlmDiagnosisAdapter::class.java)

    // OPTIONAL (not @ConfigProperty-injected): an un-seeded key must not fail config load at boot.
    // Mirrors DevOpsConfig / OpenAiCompatibleModelProvider exactly (issue #1084 precedent).
    private val apiKey: String
        get() = ConfigProvider.getConfig()
            .getOptionalValue("openbank.liveness-sentinel.model.api-key", String::class.java).orElse("")

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S)).build()
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun diagnose(finding: LivenessFinding, contextMetrics: Map<String, Double>): String {
        if (apiKey.isBlank()) {
            log.warn(
                "openbank.liveness-sentinel.model.api-key not seeded — returning placeholder diagnosis (degraded)",
            )
            return "Automated diagnosis unavailable (model API key not seeded). Finding: ${finding.title}. " +
                "Affected control: ${finding.affectedControl}."
        }
        return try {
            withContext(Dispatchers.IO) { callModel(finding, contextMetrics) }
        } catch (ex: Exception) {
            log.warnf("LLM diagnosis call failed for finding %s: %s", finding.id, ex.message)
            "Automated diagnosis failed (${ex.message}). Finding: ${finding.title}. " +
                "Affected control: ${finding.affectedControl}."
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun callModel(finding: LivenessFinding, contextMetrics: Map<String, Double>): String {
        val url = "${config.modelEndpoint().trimEnd('/')}/chat/completions"
        val payload = objectMapper.writeValueAsString(buildRequestBody(finding, contextMetrics))
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in OK_RANGE) {
            log.warnf(
                "model backend %s returned HTTP %d for finding %s",
                config.modelEndpoint(),
                resp.statusCode(),
                finding.id,
            )
            error("model backend HTTP ${resp.statusCode()}")
        }
        return parseContent(resp.body())
    }

    private fun buildRequestBody(finding: LivenessFinding, contextMetrics: Map<String, Double>) = mapOf(
        "model" to config.modelId(),
        "max_tokens" to MAX_TOKENS,
        "temperature" to TEMPERATURE,
        "messages" to listOf(
            mapOf("role" to "system", "content" to SYSTEM_PROMPT),
            mapOf("role" to "user", "content" to userPrompt(finding, contextMetrics)),
        ),
    )

    private fun userPrompt(finding: LivenessFinding, contextMetrics: Map<String, Double>): String {
        val metrics =
            if (contextMetrics.isEmpty()) "(none)" else contextMetrics.entries.joinToString { (k, v) -> "$k=$v" }
        return """
            A control-liveness finding was detected. Write a concise (2-4 sentence) root-cause
            hypothesis a human on-call engineer can act on immediately. Do not invent facts not
            present below; say so plainly if the cause is not determinable from this data alone.

            Mechanism: ${finding.mechanism}
            Severity: ${finding.severity}
            Title: ${finding.title}
            Affected control: ${finding.affectedControl}
            Raw metric value: ${finding.rawMetricValue}
            Threshold: ${finding.threshold}
            Context metrics: $metrics
        """.trimIndent()
    }

    private fun parseContent(json: String): String {
        val root = objectMapper.readTree(json)
        val choice = root.path("choices").firstOrNull() ?: error("model backend returned no choices")
        val content = choice.path("message").path("content")
        return if (content.isNull || content.isMissingNode) "" else content.asText().trim()
    }

    override suspend fun proposeFixDiff(finding: LivenessFinding, diagnosis: String): String? {
        log.infof(
            "LLM fix-diff proposal requested for finding %s mechanism=%s — deliberately unimplemented " +
                "(ADR-0163: falls back to a tracking ticket)",
            finding.id,
            finding.mechanism,
        )
        return null
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 10L
        const val REQUEST_TIMEOUT_S = 60L
        const val MAX_TOKENS = 400
        const val TEMPERATURE = 0.2
        val OK_RANGE = 200..299
        const val SYSTEM_PROMPT =
            "You are a root-cause diagnosis assistant for a banking platform's control-liveness " +
                "monitoring. You propose, you never act — your output is a diagnosis note for a human " +
                "on-call engineer, not an instruction to any system."
    }
}
