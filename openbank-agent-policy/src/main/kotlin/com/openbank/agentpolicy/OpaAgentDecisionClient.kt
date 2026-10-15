// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agentpolicy

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.BoundedBodyHandlers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration

/** Sidecar unreachable, timed out, returned a non-2xx, or an unparseable body. Distinct from a policy deny. */
class OpaGateException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Raw OPA decision for the agent query. A policy-driven DENY is data, not an error. */
sealed interface OpaAgentDecision {
    data object Allowed : OpaAgentDecision

    data class Denied(val reason: String) : OpaAgentDecision
}

/**
 * ADR-0031 D9 phase 4 (generalised from phase 3's flaky-test-hunter client): layer 2 of the
 * policy gate — the OPA sidecar query. POSTs the decision input to
 * `data.openbank.agents.decision` — the same `openbank.agents` package the MCP gate uses, with
 * object-shaped `{tier, resources}` charter grants so an agent's write path is decidable there.
 * Plain `java.net.http.HttpClient` with the fleet's size-capped [BoundedBodyHandlers]
 * (ADR-0320 P1), mirroring `openbank-libs-runtime`'s `OpaSidecarPolicyDecisionPoint` (localhost
 * sidecar infrastructure call, not an egress-allow-listed external API).
 *
 * The caller's [AgentPolicyConfig.agentId] is sent as `input.agent`; the (tool, resource, tier)
 * triple comes from the per-call [PolicyDecisionInput], so one client instance serves every
 * action of one agent identity.
 *
 * Failure contract: connectivity errors, non-2xx, and unparseable bodies all throw
 * [OpaGateException] — the caller ([AgentPolicyGate]) maps that to a DENY with reason
 * `pdp_unreachable`. This is the deliberate deviation from the agent-service MCP gate, which
 * degrades to ADVISORY when its PDP errors: that gate protects READ tools; this client fronts a
 * WRITE path whose irreversible blast radius (a real PR, a money-path change) means the
 * decision MUST fail closed.
 */
class OpaAgentDecisionClient(
    private val opaBaseUrl: String,
    private val timeout: Duration,
    private val config: AgentPolicyConfig,
) {

    private val mapper = ObjectMapper()

    /** Visible for testing: pin a pre-built client (e.g. one pointed at a loopback stub). */
    internal var httpClient: HttpClient? = null

    private val http: HttpClient by lazy {
        httpClient ?: HttpClient.newBuilder()
            .connectTimeout(timeout)
            .build()
    }

    suspend fun queryDecision(input: PolicyDecisionInput = config.defaultInput()): OpaAgentDecision =
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${opaBaseUrl.trimEnd('/')}$DECISION_PATH"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(decisionInput(input)))
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

    private fun decisionInput(input: PolicyDecisionInput): String = mapper.writeValueAsString(
        mapOf(
            "input" to mapOf(
                "agent" to config.agentId,
                "tool" to input.tool,
                "resource" to input.resource,
                "tier" to input.tier,
            ),
        ),
    )

    @Suppress("TooGenericExceptionCaught")
    private fun parse(body: String): OpaAgentDecision = try {
        val result = mapper.readTree(body).path(RESULT_NODE)
        if (result.isMissingNode || result.isNull) {
            // OPA serves no `result` for an undefined decision — fail closed as unreachable
            // shape rather than silently reading a missing `allow` as a policy deny.
            throw OpaGateException("OPA response has no result object")
        }
        if (result.path(ALLOW_NODE).asBoolean(false)) {
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
