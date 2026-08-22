// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.policy

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.casecoordinator.application.port.out.CaseCollaborationPolicyDecision
import com.openbank.casecoordinator.application.port.out.CaseCollaborationPolicyPort
import com.openbank.casecoordinator.application.port.out.CaseCollaborationPolicyQuery
import com.openbank.casecoordinator.application.port.out.CaseCollaborationPolicyUnavailable
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@ApplicationScoped
class OpaCaseCollaborationPolicyAdapter(
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "opa.url", defaultValue = "http://localhost:8181") private val baseUrl: String,
    @ConfigProperty(
        name = "opa.case-collaboration-path",
        defaultValue = "/v1/data/openbank/case_collaboration/decision",
    ) private val queryPath: String,
    @ConfigProperty(name = "opa.timeout-ms", defaultValue = "500") timeoutMs: Long,
) : CaseCollaborationPolicyPort {
    private val timeout = Duration.ofMillis(timeoutMs)
    private val http = HttpClient.newBuilder().connectTimeout(timeout).build()

    override fun decide(query: CaseCollaborationPolicyQuery): CaseCollaborationPolicyDecision {
        val input = mapOf(
            "agent" to query.agentId,
            "capability" to query.capability,
            "case_class" to query.caseClass,
            "delivery_mode" to query.deliveryMode,
        )
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$queryPath"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("input" to input))))
            .build()
        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { throw CaseCollaborationPolicyUnavailable("case policy call failed", it) }
        if (response.statusCode() !in HTTP_OK until HTTP_REDIRECT) {
            throw CaseCollaborationPolicyUnavailable("case policy returned HTTP ${response.statusCode()}")
        }
        val root = runCatching { mapper.readTree(response.body()) }
            .getOrElse { throw CaseCollaborationPolicyUnavailable("case policy returned invalid JSON", it) }
        val result = root.path("result")
        if (!result.isObject) {
            return CaseCollaborationPolicyDecision(
                false,
                "no matching policy rule",
                root.path("decision_id").asText(""),
            )
        }
        return CaseCollaborationPolicyDecision(
            allow = result.path("allow").asBoolean(false),
            reason = result.path("reason").asText("policy denied"),
            decisionId = root.path("decision_id").asText(""),
            rolloutId = result.path("rollout_id").asText(""),
            maxSignalsPerCase = result.path("max_signals_per_case").asInt(0),
        )
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_REDIRECT = 300
    }
}
