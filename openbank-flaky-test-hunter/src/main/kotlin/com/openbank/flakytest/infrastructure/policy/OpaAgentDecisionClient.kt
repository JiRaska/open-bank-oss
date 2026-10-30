// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.policy

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import com.openbank.libs.security.BoundedBodyHandlers
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest

/** Sidecar unreachable, timed out, returned a non-2xx, or an unparseable body. Distinct from a policy deny. */
class OpaGateException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Raw OPA decision for the PR-path query. A policy-driven DENY is data, not an error. */
sealed interface OpaAgentDecision {
    data object Allowed : OpaAgentDecision

    data class Denied(val reason: String) : OpaAgentDecision
}

/**
 * ADR-0031 D9 phase 3, layer 2 of the PR-path policy gate: the OPA sidecar query. POSTs the
 * decision input to `data.openbank.agents.decision` — the same `openbank.agents` package the
 * MCP gate uses, extended (ADR-0031 phase 3) with object-shaped `{tier, resources}` charter
 * grants so a background agent's write path is decidable there. Plain `java.net.http.HttpClient`,
 * mirroring `openbank-libs-runtime`'s `OpaSidecarPolicyDecisionPoint` (localhost sidecar
 * infrastructure call, not an egress-allow-listed external API).
 *
 * Failure contract: connectivity errors, non-2xx, and unparseable bodies all throw
 * [OpaGateException] — the caller (PrPathPolicyGate) maps that to a DENY with reason
 * `pdp_unreachable`. This is the deliberate deviation from the agent-service MCP gate, which
 * degrades to ADVISORY when its PDP errors: that gate protects READ tools, where a
 * fail-closed deny would take a read-only assistant offline over a sidecar blip and the worst
 * case of a wrong answer is a wrong answer. This gate protects a WRITE path that opens a real
 * pull request against the monorepo — the irreversible blast radius of an allow granted while
 * the PDP is compromised, down, or spoofed is a code change under the agent's identity, so the
 * decision MUST fail closed.
 */
@ApplicationScoped
class OpaAgentDecisionClient @Inject constructor(private val config: FlakyTestHunterConfig) {

    private val mapper = ObjectMapper()

    /** Visible for testing: pin a pre-built client (e.g. one pointed at a loopback stub). */
    internal var httpClient: HttpClient? = null

    private val http: HttpClient by lazy {
        httpClient ?: HttpClient.newBuilder()
            .connectTimeout(config.policyGate().timeout())
            .build()
    }

    suspend fun queryPrOpenDecision(): OpaAgentDecision = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.policyGate().opaUrl().trimEnd('/')}$DECISION_PATH"))
            .timeout(config.policyGate().timeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(decisionInput()))
            .build()

        @Suppress("TooGenericExceptionCaught")
        val response = try {
            http.send(request, BoundedBodyHandlers.ofString())
        } catch (ex: Exception) {
            throw OpaGateException("OPA sidecar call failed: ${ex.message}", ex)
        }
        if (response.statusCode() !in HTTP_OK_RANGE) {
            throw OpaGateException("OPA sidecar returned HTTP ${response.statusCode()}")
        }
        parse(response.body())
    }

    private fun decisionInput(): String = mapper.writeValueAsString(
        mapOf(
            "input" to mapOf(
                "agent" to PrPathGateVocabulary.AGENT_ID,
                "tool" to PrPathGateVocabulary.TOOL_GH_PR_OPEN,
                "resource" to PrPathGateVocabulary.RESOURCE_GITHUB_PR,
                "tier" to PrPathGateVocabulary.TIER_WRITE_PROPOSAL,
            ),
        ),
    )

    @Suppress("TooGenericExceptionCaught")
    private fun parse(body: String): OpaAgentDecision = try {
        val result = mapper.readTree(body).path(RESULT_NODE)
        if (!result.isObject) {
            // OPA serves no `result` for an undefined decision — fail closed as unreachable
            // shape rather than silently reading a missing `allow` as a policy deny.
            throw OpaGateException("OPA response has no result object")
        }
        val allow = result.path(ALLOW_NODE)
        if (!allow.isBoolean) {
            throw OpaGateException("OPA result has no boolean allow decision")
        }
        if (allow.booleanValue()) {
            OpaAgentDecision.Allowed
        } else {
            OpaAgentDecision.Denied(result.path(REASON_NODE).asText(NO_MATCHING_RULE))
        }
    } catch (ex: OpaGateException) {
        throw ex
    } catch (ex: Exception) {
        throw OpaGateException("Unparseable OPA response: ${ex.message}", ex)
    }

    private companion object {
        const val DECISION_PATH = "/v1/data/openbank/agents/decision"
        const val RESULT_NODE = "result"
        const val ALLOW_NODE = "allow"
        const val REASON_NODE = "reason"
        const val NO_MATCHING_RULE = "no matching allow rule"
        val HTTP_OK_RANGE = 200..299
    }
}
