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
import com.openbank.mcp.application.McpUntrustedData
import com.openbank.mcp.application.PolicyFilteredToolCatalog
import com.openbank.mcp.application.port.out.CallerIdentitySource
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.McpMetricsPort
import com.openbank.mcp.application.protocol.InitializeResult
import com.openbank.mcp.application.protocol.McpError
import com.openbank.mcp.application.protocol.McpErrorCode
import com.openbank.mcp.application.protocol.McpResponse
import com.openbank.mcp.application.protocol.ServerCapabilities
import com.openbank.mcp.application.protocol.ServerInfo
import com.openbank.mcp.application.protocol.ToolCallResult
import com.openbank.mcp.application.protocol.ToolContent
import com.openbank.mcp.application.protocol.ToolsListResult
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * The Model Context Protocol server (ADR-0181): JSON-RPC 2.0 over HTTP POST, exposing the curated
 * tool set to a governed AI agent. Mirrors agent-service's McpEndpoint, but authorizes every
 * `tools/call` through the SHARED ADR-0034 PDP as an `AI_AGENT` principal (not agent-service's
 * in-service PDP), so an MCP tool-call is gated by exactly the same policy plane as a human REST
 * call. Deny-by-default: a tool with no capability entry, or an OPA `deny`, is refused.
 *
 * Every `tools/call` — allowed, denied, unmapped, or denied by a PDP outage — emits one
 * AI-attributed [McpCallAuditor] audit event (ADR-0031 D5 / ADR-0086), so an AI-initiated action
 * against the bank is reconstructable. `tools/list` is audited too (ADR-0225 D4) and its result is
 * policy-filtered per caller: a caller sees only the tools the shared PDP would let it call, an
 * anonymous caller or a full PDP outage sees an empty list — discovery is capability-shaped, so
 * the operations vocabulary is no longer a reconnaissance map. `initialize` and `ping` touch no
 * customer data and stay unaudited.
 *
 * The same terminal branches also report to [McpMetricsPort] — audit and meter are emitted from one
 * place so the aggregate can never disagree with the per-call trail. The audit event answers "what
 * did this agent do"; the meters answer the questions a trail cannot without a query: the rate, the
 * outcome mix, the latency, and — via `caller_identity` — whether each call was attributed to a
 * validated token or failed resolution (blocker #2206's placeholder-fallback source is gone as of
 * step 4; the enum value remains only as that closed blocker's historical record).
 *
 * Caller identity (ADR-0195, step 4 cutover): the acting agent + presented consent come from the
 * caller's validated OAuth 2.1 access token via [CallerContextResolver] — `sub` (`agent:<id>`,
 * classified AI_AGENT by the shared AuthorizeInterceptor/rego and bridged to `agents.allow`) and the
 * `consent_id` claim. No agent token ⇒ [CallerContextResolver.resolveOrNull] returns null ⇒ the call
 * is denied with "Authorization unavailable" — the phase-1 placeholder identity is gone, so there is
 * no path from an unauthenticated caller to a real read. The consent's `grantedAccounts` are
 * validated LIVE at consent-service by [com.openbank.mcp.infrastructure.read.RealAccountReadPort];
 * account scope is never taken from the token.
 *
 * Known residual (unchanged by step 4): `rest.rego`'s `agent-charter-allows` bridge still drops the
 * `attributes` map on the query to `pdp.allow` above, so the shared PDP never sees `consentId` — the
 * capability-vs-charter decision is real now, but a future policy that wants to gate on consent state
 * cannot yet, since the input never reaches it (threat model §3, docs/threat-models/openbank-mcp-service.md).
 */
@Path("/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class McpEndpoint(
    private val registry: McpToolRegistry,
    private val pdp: PolicyDecisionPoint,
    private val auditor: McpCallAuditor,
    private val callerResolver: CallerContextResolver,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "mcp.server.name", defaultValue = "openbank-mcp") private val serverName: String,
    @ConfigProperty(name = "mcp.server.version", defaultValue = "0.1.0") private val serverVersion: String,
    @ConfigProperty(name = "mcp.server.protocol-version", defaultValue = "2025-06-18")
    private val protocolVersion: String,
) {

    /**
     * Field-injected rather than a constructor parameter: the constructor already carries the four
     * collaborators plus the three `@ConfigProperty` server-identity strings, and a ninth parameter
     * trips detekt's `LongParameterList`. Same shape as `LoanStageEventConsumer` / `VopRateLimitFilter`
     * elsewhere in the fleet.
     */
    @Inject
    lateinit var metrics: McpMetricsPort

    /** Field-injected for the same reason as [metrics] — see the note above. */
    @Inject
    lateinit var rateLimiter: McpRateLimiter

    /** Field-injected for the same reason as [metrics] — the policy-filtered tools/list (ADR-0225). */
    @Inject
    lateinit var toolsCatalog: PolicyFilteredToolCatalog

    private val log = Logger.getLogger(McpEndpoint::class.java)

    @POST
    @Suppress("ReturnCount")
    fun handle(body: JsonNode): Response {
        val id = body.path("id").let { if (it.isMissingNode) NullNode.instance else it }
        val method = body.path("method").asText("")
        val params = body.path("params")

        // Bounded before it becomes a tag: `method` is a caller-supplied string on a public agent
        // surface, so an unrecognised value must not mint a new metric series (cardinality contract).
        metrics.requestHandled(if (method in KNOWN_METHODS) method else McpMetricsPort.UNKNOWN_METHOD)

        val result: Any = when (method) {
            "initialize" -> InitializeResult(
                protocolVersion,
                ServerCapabilities(),
                ServerInfo(serverName, serverVersion),
                // Ships the untrusted-data marker contract to the client's model before it ever
                // sees a marker (#2412). Deliberately part of the handshake and not documentation:
                // a client that never reads the docs still gets it.
                instructions = McpUntrustedData.PREAMBLE,
            )
            "notifications/initialized" -> return Response.noContent().build()
            "ping" -> mapOf("pong" to true)
            "tools/list" -> return handleToolsList(id)
            "tools/call" -> return handleToolCall(id, params)
            else -> return error(id, McpErrorCode.METHOD_NOT_FOUND, "Method not found: $method")
        }
        return Response.ok(McpResponse(id = id, result = result)).build()
    }

    /**
     * ADR-0225: discovery is capability-shaped — the caller sees only the tools the shared PDP
     * would let it call, evaluated as the same (principal, capability) pair the call gate uses.
     * Anonymous/malformed-token callers and a full PDP outage both get an empty list, fail-closed
     * like the call path; every list is audited and metered (D4). Schemas are never mutated per
     * caller (D2) — only set membership is filtered.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun handleToolsList(id: JsonNode): Response {
        val ctx = try {
            runBlocking { callerResolver.resolveOrNull() }
        } catch (ex: Exception) {
            log.warnf("caller context resolution failed for tools/list: %s — denying", ex.message)
            null
        }
        if (ctx == null) {
            runBlocking {
                auditor.toolsListCompleted(
                    McpCallAuditor.ToolsList(
                        agentId = "unknown",
                        consentId = "none",
                        toolsReturned = 0,
                        toolsTotal = registry.tools.size,
                        pdpErrors = 0,
                        reason = "caller authentication failed",
                    ),
                )
                metrics.toolsListCompleted(McpMetricsPort.ToolsListOutcome.ANONYMOUS_DENIED)
            }
            return Response.ok(McpResponse(id = id, result = ToolsListResult(emptyList()))).build()
        }

        val filtered = runBlocking {
            toolsCatalog.visibleTools(
                principal = Principal(id = ctx.agentId, type = ctx.principalType, roles = ctx.roles),
                cacheKey = "${ctx.agentId}|${ctx.consentId}",
            )
        }
        runBlocking {
            auditor.toolsListCompleted(
                McpCallAuditor.ToolsList(
                    agentId = ctx.agentId,
                    consentId = ctx.consentId,
                    toolsReturned = filtered.tools.size,
                    toolsTotal = filtered.total,
                    pdpErrors = filtered.pdpErrors,
                    actChain = ctx.actChain,
                    sessionId = ctx.sessionId,
                ),
            )
            metrics.toolsListCompleted(
                if (filtered.tools.isEmpty() && filtered.pdpErrors > 0) {
                    McpMetricsPort.ToolsListOutcome.PDP_UNAVAILABLE
                } else {
                    McpMetricsPort.ToolsListOutcome.OK
                },
            )
        }
        return Response.ok(McpResponse(id = id, result = ToolsListResult(filtered.tools))).build()
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun handleToolCall(id: JsonNode, params: JsonNode): Response {
        val startedAt = System.nanoTime()
        val toolName = params.path("name").asText("")
        val arguments = params.path("arguments").let { if (it.isMissingNode) mapper.createObjectNode() else it }
        // Argument KEY names only — the values are customer data and must never enter the audit
        // trail (McpCallAuditor KDoc).
        val argumentKeys = arguments.fieldNames().asSequence().sorted().toList()
        // Bounded before it becomes a tag: an unmapped tool name is caller-supplied, so it is
        // reported as the single "unmapped" value rather than as itself (cardinality contract). The
        // audit event still carries the exact name — that is a per-call record, not a label.
        val toolTag = if (registry.capabilities.containsKey(toolName)) toolName else McpMetricsPort.UNMAPPED_TOOL

        // Caller authentication (ADR-0195): the acting agent + presented consent come from the
        // validated OAuth token. A malformed agent token (e.g. no consent_id) fails CLOSED — a
        // money-adjacent surface never degrades to an unscoped identity.
        val ctx = resolveCaller(toolName, argumentKeys)
            ?: return toolError(id, "Authorization unavailable")

        // Audit and meter together, at every terminal branch, so the aggregate can never disagree
        // with the per-call trail about what happened.
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
                    actChain = ctx.actChain,
                    sessionId = ctx.sessionId,
                ),
            )
            metrics.toolCallCompleted(toolTag, decision, result, Duration.ofNanos(System.nanoTime() - startedAt))
        }

        // Deny-by-default: no capability mapping ⇒ no OPA action ⇒ refuse.
        val capability = registry.capabilities[toolName]
        if (capability == null) {
            audit(null, McpCallAuditor.Decision.DENY, AuditResult.DENIED, "no capability mapping")
            return toolError(id, "Tool not permitted: $toolName")
        }

        // Gate on the SHARED ADR-0034 PDP (input.action = the capability). The principal class
        // follows the token: AI_AGENT for consent-bound agent tokens, HUMAN with its bounded realm
        // roles for an OBO staff token (ADR-0224) — the same role vocabulary a REST call presents.
        val decision = try {
            runBlocking {
                pdp.allow(
                    AuthzQuery(
                        principal = Principal(id = ctx.agentId, type = ctx.principalType, roles = ctx.roles),
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

        // Rate limit AFTER the PDP decision, so a denied caller cannot burn the acting agent's
        // window, and BEFORE the tool executes, which is the fan-out into consent/account/balance/
        // transaction-service this bounds (#2409). A throttled call is a DENY like any other: same
        // audit event, same meter, so the aggregate cannot disagree with the trail.
        val limit = rateLimiter.check(ctx.agentId)
        if (limit != McpRateLimiter.Outcome.ALLOWED) {
            audit(capability, McpCallAuditor.Decision.DENY, AuditResult.DENIED, limit.reason)
            return toolError(id, "Rate limit exceeded: ${limit.reason}")
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

    // Resolve the caller's identity + consent, or audit the failure and return null (the caller
    // then denies with "Authorization unavailable"). Fails closed on any resolution error.
    @Suppress("TooGenericExceptionCaught")
    private fun resolveCaller(toolName: String, argumentKeys: List<String>): ConsentContext? = try {
        resolveContext()
    } catch (ex: Exception) {
        log.warnf("caller context resolution failed for %s: %s — denying", toolName, ex.message)
        metrics.callerIdentityResolved(CallerIdentitySource.RESOLUTION_FAILED)
        runBlocking {
            auditor.toolCallCompleted(
                McpCallAuditor.ToolCall(
                    agentId = "unknown",
                    consentId = "none",
                    tool = toolName,
                    capability = null,
                    decision = McpCallAuditor.Decision.UNAVAILABLE,
                    result = AuditResult.DENIED,
                    reason = "caller authentication failed",
                    argumentKeys = argumentKeys,
                ),
            )
        }
        null
    }

    // ADR-0195 step 4: the phase-1 placeholder identity is REMOVED. The acting agent + consent come
    // ONLY from a validated OAuth token (CallerContextResolver) — no agent token is treated the same
    // as a malformed one: resolveCaller's catch below denies, audits, and meters
    // CallerIdentitySource.RESOLUTION_FAILED. ANONYMOUS_FALLBACK can no longer fire; it stays in the
    // enum as the historical record of the #2206 blocker this cutover closes — the metric that used
    // to track it now reads zero by construction, not because fallback traffic happened to taper off.
    private fun resolveContext(): ConsentContext {
        val ctx = runBlocking { callerResolver.resolveOrNull() } ?: error("no agent token presented")
        metrics.callerIdentityResolved(CallerIdentitySource.TOKEN)
        return ctx
    }

    private fun toolError(id: JsonNode, message: String): Response = Response.ok(
        McpResponse(id = id, result = ToolCallResult(listOf(ToolContent(text = message)), isError = true)),
    ).build()

    private fun error(id: JsonNode, code: Int, message: String): Response =
        Response.ok(McpResponse(id = id, error = McpError(code, message))).build()

    private companion object {
        /**
         * The JSON-RPC methods this server implements. Used ONLY to bound the `method` metric tag —
         * the dispatch itself is the `when` in [handle], which stays the single source of behaviour.
         */
        val KNOWN_METHODS = setOf("initialize", "notifications/initialized", "ping", "tools/list", "tools/call")
    }
}
