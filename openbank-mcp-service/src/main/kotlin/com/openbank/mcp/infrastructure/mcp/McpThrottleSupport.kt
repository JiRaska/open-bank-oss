// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import com.openbank.libs.audit.AuditResult
import com.openbank.mcp.application.McpCallAuditor
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.McpMetricsPort
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import kotlinx.coroutines.runBlocking

/**
 * The throttle-refusal bookkeeping of [McpEndpoint] (ADR-0383 D5), kept out of the endpoint so the
 * endpoint stays one authorization sequence. Every refused request is METERED; the audit trail gets
 * at most ONE row per caller (or per the shared anonymous bucket) per window
 * ([McpRateLimiter.shouldAuditThrottle]), so a throttled flood cannot turn into an unbounded
 * audit-write amplifier. Constructed lazily by the endpoint because its collaborators are
 * field-injected.
 */
internal class McpThrottleSupport(
    private val auditor: McpCallAuditor,
    private val metrics: McpMetricsPort,
    private val limiter: McpRateLimiter,
) {

    /**
     * Applies the rate window, then the in-flight cap. Returns the refusal reason, or null when the
     * call may proceed — in which case an in-flight slot is held and MUST be released by the caller.
     */
    fun refusalReason(agentId: String): String? {
        val limit = limiter.check(agentId)
        return when {
            limit != McpRateLimiter.Outcome.ALLOWED -> limit.reason
            !limiter.tryAcquire(agentId) -> limiter.inFlightReason
            else -> null
        }
    }

    /** Meters a refused authenticated `tools/call`; true when this one should also be audited. */
    fun callRefused(agentId: String): Boolean {
        metrics.requestThrottled(McpMetricsPort.ThrottleSurface.TOOLS_CALL, McpMetricsPort.ThrottleCaller.AUTHENTICATED)
        return limiter.shouldAuditThrottle(agentId)
    }

    /**
     * Spends the anonymous bucket for a `tools/call` with no resolvable caller. Returns the refusal
     * reason when throttled (already metered and, once per window, audited), null when within budget.
     */
    fun anonymousCallRefusal(toolName: String, argumentKeys: List<String>): String? {
        val limit = limiter.checkAnonymous()
        if (limit == McpRateLimiter.Outcome.ALLOWED) return null
        metrics.requestThrottled(McpMetricsPort.ThrottleSurface.TOOLS_CALL, McpMetricsPort.ThrottleCaller.ANONYMOUS)
        if (limiter.shouldAuditThrottle(null)) {
            auditAnonymousCall(toolName, argumentKeys, limit.reason ?: "rate limit exceeded")
        }
        return limit.reason
    }

    /** The audit row for an unauthenticated call that was within the anonymous budget. */
    fun auditAnonymousCall(toolName: String, argumentKeys: List<String>, reason: String) = runBlocking {
        auditor.toolCallCompleted(
            McpCallAuditor.ToolCall(
                agentId = "unknown",
                consentId = "none",
                tool = toolName,
                capability = null,
                decision = McpCallAuditor.Decision.UNAVAILABLE,
                result = AuditResult.DENIED,
                reason = reason,
                argumentKeys = argumentKeys,
            ),
        )
    }

    /**
     * Throttle gate for `tools/list`: resolved callers spend their own window, the rest the shared
     * anonymous bucket. Returns the refusal reason (metered, audited once per window) or null.
     */
    fun toolsListRefusal(ctx: ConsentContext?, toolsTotal: Int): String? {
        val limit = if (ctx == null) limiter.checkAnonymous() else limiter.check(ctx.agentId)
        if (limit == McpRateLimiter.Outcome.ALLOWED) return null
        metrics.requestThrottled(
            McpMetricsPort.ThrottleSurface.TOOLS_LIST,
            if (ctx == null) McpMetricsPort.ThrottleCaller.ANONYMOUS else McpMetricsPort.ThrottleCaller.AUTHENTICATED,
        )
        if (limiter.shouldAuditThrottle(ctx?.agentId)) {
            runBlocking {
                auditor.toolsListCompleted(
                    McpCallAuditor.ToolsList(
                        agentId = ctx?.agentId ?: "unknown",
                        consentId = ctx?.consentId ?: "none",
                        toolsReturned = 0,
                        toolsTotal = toolsTotal,
                        pdpErrors = 0,
                        reason = limit.reason,
                        actChain = ctx?.actChain ?: emptyList(),
                        sessionId = ctx?.sessionId,
                    ),
                )
            }
        }
        return limit.reason
    }
}
