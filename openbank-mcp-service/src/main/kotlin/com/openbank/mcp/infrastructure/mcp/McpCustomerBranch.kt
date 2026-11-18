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
 * An ADMITTED customer reaches no tool in this slice: `tools/list` is empty and `tools/call` is
 * refused and audited under the customer's own subject. Customer reads need the `MCP_CLIENT` grant
 * lookup (ADR-0383 P2/D3) and per-service party enforcement (P5), neither of which exists yet; the
 * staff tool path is never offered as a substitute.
 */
internal class McpCustomerBranch(
    private val admission: CustomerChannelAdmission,
    private val auditor: McpCallAuditor,
    private val metrics: McpMetricsPort,
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
        return when (val outcome = runBlocking { admission.admit(jwt, authorization, dpop) }) {
            is Outcome.Refused -> refuse(outcome, method, params)
            is Outcome.Admitted -> admitted(outcome, method, id, params)
        }
    }

    private fun refuse(outcome: Outcome.Refused, method: String, params: JsonNode): Response {
        log.warnf("customer channel refused %s: %s", McpInputBounds.logSafe(method), outcome.reason)
        metrics.callerIdentityResolved(CallerIdentitySource.RESOLUTION_FAILED)
        if (method == TOOLS_CALL && !McpInputBounds.containsNul(params)) {
            audit(UNKNOWN, McpInputBounds.echo(params.path("name").asText("")), params, outcome.reason, emptyList())
        }
        val builder = Response.status(outcome.status)
        outcome.challenge?.let { builder.header(WWW_AUTHENTICATE, it) }
        return builder.build()
    }

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
        const val NO_CONSENT = "none"
        const val NOT_ENABLED = "customer channel tools not enabled (ADR-0383 P2/P5)"
    }
}
