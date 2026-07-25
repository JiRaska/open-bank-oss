// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.authz.Principal
import com.openbank.mcp.application.McpCallAuditor
import com.openbank.mcp.application.McpToolRegistry
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.protocol.InitializeResult
import com.openbank.mcp.application.protocol.McpError
import com.openbank.mcp.application.protocol.McpErrorCode
import com.openbank.mcp.application.protocol.McpResponse
import com.openbank.mcp.application.protocol.ServerCapabilities
import com.openbank.mcp.application.protocol.ServerInfo
import com.openbank.mcp.application.protocol.ToolCallResult
import com.openbank.mcp.application.protocol.ToolContent
import com.openbank.mcp.application.protocol.ToolsListResult
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * The Model Context Protocol server (ADR-0181): JSON-RPC 2.0 over HTTP POST, exposing the curated
 * tool set to a governed AI agent. Mirrors agent-service's McpEndpoint, but authorizes every
 * `tools/call` through the SHARED ADR-0034 PDP as an `AI_AGENT` principal (not agent-service's
 * in-service PDP), so an MCP tool-call is gated by exactly the same policy plane as a human REST
 * call. Deny-by-default: a tool with no capability entry, or an OPA `deny`, is refused.
 *
 * Every `tools/call` — allowed, denied, unmapped, or denied by a PDP outage — emits one
 * AI-attributed [McpCallAuditor] audit event (ADR-0031 D5 / ADR-0086), so an AI-initiated action
 * against the bank is reconstructable. `tools/list`, `initialize` and `ping` do not: they touch no
 * customer data and expose only the static tool catalogue.
 *
 * Phase 1: the acting agent + consent are resolved from headers (X-Agent-Id / X-Consent-Id); the
 * real OAuth 2.1 → PSD2-consent binding (ADR-0126) is phase 2. The principal id is `agent:`-prefixed
 * so the shared AuthorizeInterceptor/rego classify it AI_AGENT and bridge to `agents.allow`.
 */
@Path("/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class McpEndpoint(
    private val registry: McpToolRegistry,
    private val pdp: PolicyDecisionPoint,
    private val auditor: McpCallAuditor,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "mcp.server.name", defaultValue = "openbank-mcp") private val serverName: String,
    @ConfigProperty(name = "mcp.server.version", defaultValue = "0.1.0") private val serverVersion: String,
    @ConfigProperty(name = "mcp.server.protocol-version", defaultValue = "2025-06-18")
    private val protocolVersion: String,
) {

    private val log = Logger.getLogger(McpEndpoint::class.java)

    @POST
    @Suppress("ReturnCount")
    fun handle(body: JsonNode): Response {
        val id = body.path("id").let { if (it.isMissingNode) NullNode.instance else it }
        val method = body.path("method").asText("")
        val params = body.path("params")

        val result: Any = when (method) {
            "initialize" -> InitializeResult(
                protocolVersion,
                ServerCapabilities(),
                ServerInfo(serverName, serverVersion),
            )
            "notifications/initialized" -> return Response.noContent().build()
            "ping" -> mapOf("pong" to true)
            "tools/list" -> ToolsListResult(registry.tools)
            "tools/call" -> return handleToolCall(id, params)
            else -> return error(id, McpErrorCode.METHOD_NOT_FOUND, "Method not found: $method")
        }
        return Response.ok(McpResponse(id = id, result = result)).build()
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun handleToolCall(id: JsonNode, params: JsonNode): Response {
        val toolName = params.path("name").asText("")
        val arguments = params.path("arguments").let { if (it.isMissingNode) mapper.createObjectNode() else it }
        val ctx = resolveContext()
        // Argument KEY names only — the values are customer data and must never enter the audit
        // trail (McpCallAuditor KDoc).
        val argumentKeys = arguments.fieldNames().asSequence().sorted().toList()

        fun audit(
            capability: String?,
            decision: McpCallAuditor.Decision,
            result: AuditResult,
            reason: String? = null,
        ) = runBlocking {
            auditor.toolCallCompleted(
                McpCallAuditor.ToolCall(
                    agentId = ctx.agentId,
                    consentId = ctx.consentId,
                    tool = toolName,
                    capability = capability,
                    decision = decision,
                    result = result,
                    reason = reason,
                    argumentKeys = argumentKeys,
                ),
            )
        }

        // Deny-by-default: no capability mapping ⇒ no OPA action ⇒ refuse.
        val capability = registry.capabilities[toolName]
        if (capability == null) {
            audit(null, McpCallAuditor.Decision.DENY, AuditResult.DENIED, "no capability mapping")
            return toolError(id, "Tool not permitted: $toolName")
        }

        // Gate on the SHARED ADR-0034 PDP as an AI_AGENT principal (input.action = the capability).
        val decision = try {
            runBlocking {
                pdp.allow(
                    AuthzQuery(
                        principal = Principal(id = ctx.agentId, type = "AI_AGENT"),
                        action = capability,
                        resource = null,
                        attributes = mapOf("tool" to toolName, "consentId" to ctx.consentId),
                    ),
                )
            }
        } catch (ex: Exception) {
            // Fail closed: a PDP outage denies (never fail-open on a money-adjacent surface).
            log.warnf("PDP error authorizing %s: %s — denying", toolName, ex.message)
            audit(capability, McpCallAuditor.Decision.UNAVAILABLE, AuditResult.DENIED, "pdp unavailable")
            return toolError(id, "Authorization unavailable")
        }
        if (!decision.allow) {
            val reason = decision.reason ?: "no matching allow rule"
            audit(capability, McpCallAuditor.Decision.DENY, AuditResult.DENIED, reason)
            return toolError(id, "Denied by policy: $reason")
        }

        return try {
            val result = registry.call(toolName, arguments, ctx)
            audit(capability, McpCallAuditor.Decision.ALLOW, AuditResult.SUCCESS)
            Response.ok(McpResponse(id = id, result = result)).build()
        } catch (ex: IllegalArgumentException) {
            audit(capability, McpCallAuditor.Decision.ALLOW, AuditResult.FAILURE, "invalid params")
            error(id, McpErrorCode.INVALID_PARAMS, ex.message ?: "invalid params")
        } catch (ex: Exception) {
            log.warnf("tool %s failed: %s", toolName, ex.message)
            audit(capability, McpCallAuditor.Decision.ALLOW, AuditResult.FAILURE, "tool error")
            Response.ok(
                McpResponse(id = id, result = ToolCallResult(listOf(ToolContent(text = "tool error")), isError = true)),
            ).build()
        }
    }

    // Phase 1 identity: headers. Phase 2: OAuth 2.1 token -> PSD2 consent (grantedAccounts).
    private fun resolveContext(): ConsentContext {
        // Placeholder resolution; the real binding lands with the OAuth resource-server config.
        return ConsentContext(agentId = "agent:mcp-anonymous", consentId = "none", grantedAccounts = emptyList())
    }

    private fun toolError(id: JsonNode, message: String): Response = Response.ok(
        McpResponse(id = id, result = ToolCallResult(listOf(ToolContent(text = message)), isError = true)),
    ).build()

    private fun error(id: JsonNode, code: Int, message: String): Response =
        Response.ok(McpResponse(id = id, error = McpError(code, message))).build()
}
