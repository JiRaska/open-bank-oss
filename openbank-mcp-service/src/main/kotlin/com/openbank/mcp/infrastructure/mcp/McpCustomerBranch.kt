// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.audit.AuditResult
import com.openbank.mcp.application.McpCallAuditor
import com.openbank.mcp.application.McpInputBounds
import com.openbank.mcp.application.port.out.CallerIdentitySource
import com.openbank.mcp.application.port.out.McpMetricsPort
import com.openbank.mcp.application.protocol.McpResponse
import com.openbank.mcp.application.protocol.ToolCallResult
import com.openbank.mcp.application.protocol.ToolContent
import com.openbank.mcp.application.protocol.ToolsListResult
import com.openbank.mcp.infrastructure.customer.CustomerChannelAdmission
import com.openbank.mcp.infrastructure.customer.CustomerChannelAdmission.Outcome
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.jwt.JsonWebToken
import org.jboss.logging.Logger

/**
 * The customer-channel branch of `/mcp` (ADR-0383 D1, ADR-0385 D1), taken only while the channel is
 * enabled. Runs BEFORE the staff audience check and before any tool dispatch:
 * - no token at all → 401 carrying the RFC 9728 `resource_metadata` and the DPoP scheme;
 * - a customers-realm token → admission (aud, allowlisted `azp`, `cnf.jkt`, DPoP proof); a refusal
 *   is answered here and never reaches the staff path;
 * - any other token → `null`, the staff path handles it (and [CallerContextResolver] refuses a
 *   customers-realm token there independently).
 *
 * Admitted calls are bounded by the per-(subject, client) window spent inside admission, BEFORE the
 * DPoP replay claim; an admitted call is therefore audited each time, within that window.
 *
 * An ADMITTED customer reaches no tool in this slice: `tools/list` is empty and `tools/call` is
 * refused and audited under the customer's own subject. Customer reads need the `MCP_CLIENT` grant
 * lookup (ADR-0383 P2/D3) and per-service party enforcement (P5), neither of which exists yet; the
 * staff tool path is never offered as a substitute.
 */
internal class McpCustomerBranch(
    private val admission: CustomerChannelAdmission,
    private val auditor: McpCallAuditor,
    private val metrics: McpMetricsPort,
    private val limiter: McpRateLimiter,
    /** The shared (Valkey) D5.3 budgets when configured; null = the in-process [limiter]. */
    private val shared: CustomerChannelAdmission.Throttles? = null,
    /** The subject-less window key for this request ([ClientAddressResolver]). */
    private val clientKey: () -> String = { ClientAddressKey.UNKNOWN },
) {
    private val log = Logger.getLogger(McpCustomerBranch::class.java)

    @Suppress("ReturnCount")
    fun handle(
        jwt: JsonWebToken,
        authorization: String?,
        dpop: List<String>,
        method: String,
        id: JsonNode,
        params: JsonNode,
    ): Response? {
        if (jwt.subject == null && authorization.isNullOrBlank()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .header(WWW_AUTHENTICATE, admission.missingTokenChallenge())
                .build()
        }
        if (!admission.isCustomerToken(jwt)) return null
        val outcome = runBlocking { admission.admit(jwt, authorization, dpop, throttles) }
        return when (outcome) {
            is Outcome.Refused -> refuse(outcome, jwt.subject, method, params)
            is Outcome.Admitted -> admitted(outcome, method, id, params)
        }
    }

    /**
     * ADR-0383 D5.3 budgets — the shared Valkey windows when configured, else the in-process
     * limiter; which run before the DPoP check is decided in admission. Every refusal is metered.
     */
    private val throttles = object : CustomerChannelAdmission.Throttles {
        // With a shared store configured the local limiter is never consulted, not even as a fallback:
        // a shared-store failure throws (503), it does not degrade to per-pod budgets.
        override suspend fun proofKey(thumbprint: String) = metered(
            if (shared != null) {
                shared.proofKey(thumbprint)
            } else {
                reason(limiter.check(CustomerChannelAdmission.proofKeyThrottleKey(thumbprint)))
            },
        )

        override suspend fun client(clientId: String) = metered(
            if (shared != null) {
                shared.client(clientId)
            } else {
                reason(limiter.checkShared(CustomerChannelAdmission.clientThrottleKey(clientId)))
            },
        )

        override suspend fun subject(subject: String, clientId: String) = metered(
            if (shared != null) {
                shared.subject(subject, clientId)
            } else {
                reason(limiter.check(CustomerChannelAdmission.throttleKey(subject, clientId)))
            },
        )
    }

    private fun reason(outcome: McpRateLimiter.Outcome): String? =
        outcome.takeUnless { it == McpRateLimiter.Outcome.ALLOWED }?.let { it.reason ?: "rate limit exceeded" }

    private fun metered(reason: String?): String? = reason?.also {
        metrics.requestThrottled(
            McpMetricsPort.ThrottleSurface.TOOLS_CALL,
            McpMetricsPort.ThrottleCaller.AUTHENTICATED,
        )
    }

    /**
     * A refusal writes at most ONE audit row per subject per minute (the same window the staff path
     * uses for throttled calls, `McpRateLimiter.shouldAuditThrottle`), so a flood of refused calls —
     * throttled or not — cannot become an unbounded audit-write amplifier. Every refusal is metered.
     */
    private fun refuse(outcome: Outcome.Refused, subject: String?, method: String, params: JsonNode): Response {
        metrics.callerIdentityResolved(CallerIdentitySource.RESOLUTION_FAILED)
        val auditable = limiter.shouldAuditThrottle(REFUSAL_KEY_PREFIX + refusalSource(subject))
        if (auditable) log.warnf("customer channel refused %s: %s", McpInputBounds.logSafe(method), outcome.reason)
        if (auditable && method == TOOLS_CALL && !McpInputBounds.containsNul(params)) {
            audit(UNKNOWN, McpInputBounds.echo(params.path("name").asText("")), params, outcome.reason, emptyList())
        }
        val builder = Response.status(outcome.status)
        outcome.challenge?.let { builder.header(WWW_AUTHENTICATE, it) }
        return builder.build()
    }

    /**
     * The refusal window key: the token subject when there is one, otherwise the client NETWORK
     * ([ClientAddressKey], IPv6 /64, #16847) — never one shared key, which would let one caller's
     * flood suppress every other subject-less caller's audit row.
     */
    private fun refusalSource(subject: String?): String =
        subject?.let { "sub:$it" } ?: runCatching(clientKey).getOrDefault(ClientAddressKey.UNKNOWN)

    private fun admitted(outcome: Outcome.Admitted, method: String, id: JsonNode, params: JsonNode): Response? {
        val ctx = outcome.context
        return when (method) {
            "tools/list" -> {
                metrics.callerIdentityResolved(CallerIdentitySource.TOKEN)
                runBlocking {
                    auditor.toolsListCompleted(
                        McpCallAuditor.ToolsList(
                            agentId = ctx.agentId,
                            consentId = NO_CONSENT,
                            toolsReturned = 0,
                            toolsTotal = 0,
                            pdpErrors = 0,
                            reason = NOT_ENABLED,
                            actChain = ctx.actChain,
                        ),
                    )
                }
                Response.ok(McpResponse(id = id, result = ToolsListResult(emptyList()))).build()
            }
            TOOLS_CALL -> {
                metrics.callerIdentityResolved(CallerIdentitySource.TOKEN)
                if (!McpInputBounds.containsNul(params)) {
                    val tool = McpInputBounds.echo(params.path("name").asText(""))
                    audit(ctx.agentId, tool, params, NOT_ENABLED, ctx.actChain)
                }
                Response.ok(
                    McpResponse(
                        id = id,
                        result = ToolCallResult(
                            listOf(ToolContent(text = "Not available on this channel")),
                            isError = true,
                        ),
                    ),
                ).build()
            }
            // initialize / ping / notifications and unknown methods carry no data: the shared dispatch answers them.
            else -> null
        }
    }

    private fun audit(agentId: String, tool: String, params: JsonNode, reason: String, actChain: List<String>) =
        runBlocking {
            auditor.toolCallCompleted(
                McpCallAuditor.ToolCall(
                    agentId = agentId,
                    consentId = NO_CONSENT,
                    tool = tool,
                    capability = null,
                    decision = McpCallAuditor.Decision.DENY,
                    result = AuditResult.DENIED,
                    reason = reason,
                    argumentKeys = McpInputBounds.auditKeys(params.path("arguments")),
                    actChain = actChain,
                    sessionId = null,
                ),
            )
        }

    private companion object {
        const val WWW_AUTHENTICATE = "WWW-Authenticate"
        const val TOOLS_CALL = "tools/call"
        const val UNKNOWN = "unknown"
        const val REFUSAL_KEY_PREFIX = "customer-refused:"
        const val NO_CONSENT = "none"
        const val NOT_ENABLED = "customer channel tools not enabled (ADR-0383 P2/P5)"
    }
}
