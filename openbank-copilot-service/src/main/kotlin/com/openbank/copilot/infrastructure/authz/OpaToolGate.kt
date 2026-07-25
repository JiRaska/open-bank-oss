// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.authz

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.application.port.out.ToolPolicyDecision
import com.openbank.copilot.application.port.out.ToolPolicyPort
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional

/**
 * OPA-backed adapter for [ToolPolicyPort] (ADR-0089 D3, ADR-0034, Track A).
 *
 * Calls the copilot-specific OPA policy endpoint
 * POST {opa.url}/v1/data/openbank/copilot/tool/allow
 * with input { tool, customerId, amount? } and fails closed on any error or deny.
 *
 * Fail-closed means *every* ambiguity is a deny: a malformed body, a non-2xx status, an unreachable
 * sidecar. The verdict is returned, never thrown — mapping a deny onto an HTTP status is the calling
 * REST resource's business, and the chat loop degrades gracefully for the model instead.
 *
 * This is the ENFORCE-phase complement to the whitelist `CopilotPolicyGate` — both run in
 * concert; this one adds per-tool, per-amount, per-customer OPA policy evaluation.
 */
@ApplicationScoped
class OpaToolGate(
    @ConfigProperty(name = "copilot.opa.url")
    private val opaUrlOverride: Optional<String>,
    @ConfigProperty(name = "opa.url", defaultValue = "http://localhost:8181")
    private val opaUrlFallback: String,
    private val mapper: ObjectMapper,
) : ToolPolicyPort {
    private val log = Logger.getLogger(OpaToolGate::class.java)

    private val effectiveOpaUrl: String
        get() = opaUrlOverride.orElse(opaUrlFallback)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(OPA_TIMEOUT_MS))
        .build()

    override fun authorize(toolName: String, customerId: String, amount: String?): ToolPolicyDecision {
        val input = buildMap<String, Any?> {
            put("tool", toolName)
            put("customerId", customerId)
            if (amount != null) put("amount", amount)
        }
        val body = mapper.writeValueAsString(mapOf("input" to input))
        val url = "$effectiveOpaUrl$OPA_TOOL_PATH"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(OPA_TIMEOUT_MS))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { e ->
                log.warnf(e, "OpaToolGate: sidecar unreachable for tool=%s", toolName)
                return ToolPolicyDecision.denied("opa-unreachable")
            }

        if (response.statusCode() !in HTTP_OK_RANGE) {
            log.warnf("OpaToolGate: OPA returned HTTP %d for tool=%s", response.statusCode(), toolName)
            return ToolPolicyDecision.denied("opa-error: HTTP ${response.statusCode()}")
        }

        if (!parseAllowed(response.body())) {
            log.warnf("OpaToolGate: tool=%s denied for customer=%s", toolName, customerId)
            return ToolPolicyDecision.denied("opa-denied: policy")
        }

        return ToolPolicyDecision.ALLOWED
    }

    private fun parseAllowed(body: String): Boolean = runCatching {
        val root = mapper.readTree(body)
        val result = root.path("result")
        when {
            result.isMissingNode || result.isNull -> false
            result.isBoolean -> result.asBoolean()
            else -> result.path("allow").asBoolean(false)
        }
    }.getOrElse { false }

    private companion object {
        const val OPA_TOOL_PATH = "/v1/data/openbank/copilot/tool/allow"
        const val OPA_TIMEOUT_MS = 500L
        val HTTP_OK_RANGE = 200..299
    }
}
