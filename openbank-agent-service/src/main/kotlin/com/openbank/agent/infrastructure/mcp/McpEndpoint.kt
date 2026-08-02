// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.AgentIdentityBinding
import com.openbank.agent.application.AgentPolicyGate
import com.openbank.agent.application.AgentSvidVerifier
import com.openbank.agent.application.CharterRegistry
import com.openbank.agent.application.McpToolRegistry
import com.openbank.agent.application.SvidResult
import com.openbank.agent.domain.InitializeResult
import com.openbank.agent.domain.McpError
import com.openbank.agent.domain.McpErrorCode
import com.openbank.agent.domain.McpResponse
import com.openbank.agent.domain.ServerCapabilities
import com.openbank.agent.domain.ServerInfo
import com.openbank.agent.domain.ToolCallResult
import com.openbank.agent.domain.ToolContent
import com.openbank.agent.domain.ToolsListResult
import com.openbank.agent.domain.policy.AgentIdentity
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock

// CodeQL java/log-injection: reason/attemptedAgent/method below trace back to the request
// (an identity claim asserted by the caller). Strip CR/LF so an attacker can't forge
// additional log lines (log forging, CWE-117). Top-level, not a class member, so it doesn't
// count against McpEndpoint's detekt TooManyFunctions budget.
//
// It lives ABOVE McpEndpoint's KDoc on purpose. A Kotlin annotation binds to the next
// declaration, so while this function sat between `@Path("/mcp")` and `class McpEndpoint`,
// the @Path bound to *it*: the class carried no @Path, RESTEasy never registered the
// resource, and every POST /mcp answered 404 on a running pod while /agent/chat and
// /api/v1/proposals were served normally. Nothing else changed shape — the class was still
// a CDI bean, still compiled, still passed every unit test. McpEndpointRoutingIT is what
// notices now. Never move a top-level declaration in between an annotation and its target.
private fun String?.sanitizeForLog(): String = (this ?: "-").replace('\n', '_').replace('\r', '_')

/**
 * ADR-0031 D3: the MCP surface now requires an authenticated operator (Keycloak bearer from the
 * admin-ui BFF). The bearer proves WHO is calling (a logged-in operator with an agent-capable
 * role); the X-Agent-Id header still names WHICH agent identity to run, but it can no longer be
 * forged by anything that merely reached the pod — a caller must first hold a valid operator
 * token. The charter allow-list + OPA gate still bound what that agent may do. OIDC is off in the
 * %dev/%test profile (no inbound auth there), so the unit/contract tests are unaffected.
 */
@Path("/mcp")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
@ApplicationScoped
class McpEndpoint {

    @Inject lateinit var registry: McpToolRegistry

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var policyGate: AgentPolicyGate

    @Inject lateinit var charterRegistry: CharterRegistry

    @Inject lateinit var binding: AgentIdentityBinding

    @Inject lateinit var identity: SecurityIdentity

    @Inject lateinit var auditPublisher: AuditEventPublisher

    @Inject lateinit var svid: AgentSvidVerifier

    // D3b: when true a missing/invalid SVID is rejected instead of falling back to the D3a header
    // binding. Default false so the binding stays in force until PR5b-2 has the BFF presenting certs.
    @ConfigProperty(name = "agent.identity.svid.enforced", defaultValue = "false")
    var svidEnforced: Boolean = false

    // Plain field (no CDI Clock producer in this service); overridden in tests for deterministic time.
    var clock: Clock = Clock.systemUTC()

    @Context lateinit var headers: HttpHeaders

    @ConfigProperty(name = "mcp.server.name")
    lateinit var serverName: String

    @ConfigProperty(name = "mcp.server.version")
    lateinit var serverVersion: String

    @ConfigProperty(name = "mcp.server.protocol-version")
    lateinit var protocolVersion: String

    private val log = Logger.getLogger(McpEndpoint::class.java)

    @POST
    fun handle(body: JsonNode): Response {
        val id = body["id"]
        val method =
            body["method"]?.asText() ?: return errorResponse(null, McpErrorCode.INVALID_REQUEST, "Missing method")
        val params = body["params"]

        log.debugf("MCP request: method=%s id=%s", method, id)

        val result: Any = when (method) {
            "initialize" -> handleInitialize()
            "notifications/initialized" -> return Response.noContent().build()
            "tools/list" -> handleToolsList()
            "tools/call" -> handleToolCall(params)
            "ping" -> mapOf("pong" to true)
            else -> return errorResponse(id, McpErrorCode.METHOD_NOT_FOUND, "Method not found: $method")
        }

        return Response.ok(McpResponse(id = id, result = result)).build()
    }

    private fun handleInitialize() = InitializeResult(
        protocolVersion = protocolVersion,
        capabilities = ServerCapabilities(),
        serverInfo = ServerInfo(name = serverName, version = serverVersion),
    )

    // ADR-0080 P0: advertise only the tools the calling agent's charter allows. With the
    // admin-ui BFF asserting X-Agent-Id: ui-assistant, the chat surface no longer even sees
    // AML/sanctions/payments tool schemas (reduces the FIND-S4-03 disclosure surface). No
    // agent id, or an agent with no configured allow-list → full list (call path still gates).
    private fun handleToolsList(): ToolsListResult {
        // ADR-0031 D3: a rejected identity (forged SVID, or a header binding the caller may not
        // assume) must NOT fall through to the full tool list — advertise nothing. A resolved-but-
        // absent identity (no SVID, no header) keeps the legacy full list (the call path still gates).
        val agentId = when (val res = resolveAgentId()) {
            Resolution.Rejected -> return ToolsListResult(tools = emptyList())
            is Resolution.Allowed -> res.agentId
        }
        val allowed = agentId?.let { charterRegistry.allowedCapabilities(it) } ?: emptySet()
        val tools = if (allowed.isEmpty()) {
            registry.tools
        } else {
            registry.tools.filter { registry.capabilityOf(it.name) in allowed }
        }
        // Tag each advertised tool with its downstream service + domain (#744) so the admin-ui
        // coverage grid can group verb-first tools without a name-prefix heuristic.
        return ToolsListResult(
            tools = tools.map { it.copy(service = registry.serviceOf(it.name), domain = registry.domainOf(it.name)) },
        )
    }

    private fun handleToolCall(params: JsonNode?): ToolCallResult {
        val toolName = params?.get("name")?.asText()
            ?: throw IllegalArgumentException("Missing tool name")
        val arguments = params["arguments"]

        val identity = agentIdentity(arguments)
        val outcome = policyGate.authorize(
            identity = identity,
            tool = toolName,
            capability = registry.capabilityOf(toolName),
            resource = resourceOf(arguments),
        )
        if (!outcome.proceed) {
            return ToolCallResult(
                content = listOf(ToolContent(text = "Policy denied tool '$toolName': ${outcome.decision.reason}")),
                isError = true,
            )
        }

        return registry.call(toolName, arguments, actorId = identity?.agentId ?: "unknown")
    }

    private fun agentIdentity(arguments: JsonNode?): AgentIdentity? {
        val agentId = when (val res = resolveAgentId()) {
            Resolution.Rejected -> return null
            is Resolution.Allowed -> res.agentId ?: return null
        }
        return AgentIdentity(
            agentId = agentId,
            plane = headers.getHeaderString("X-Agent-Plane")?.takeIf { it.isNotBlank() },
            skill = arguments?.get("skill")?.asText()?.takeIf { it.isNotBlank() },
        )
    }

    /** The resolved agent identity for a request. [Allowed.agentId] is null for the legacy no-identity case. */
    private sealed interface Resolution {
        data class Allowed(val agentId: String?) : Resolution
        data object Rejected : Resolution
    }

    /**
     * Resolve the calling agent identity, strongest evidence first (ADR-0031 D3):
     *  1. **D3b SVID** — a PoP-signed `pki-agent` cert. A valid cert's CN is the identity; an
     *     invalid one is rejected (audited). When SVID is enforced, its absence is also a rejection.
     *  2. **D3a header binding** (fallback while SVID is not yet presented) — the `X-Agent-Id` header
     *     bound to the operator's verified roles, deny-by-default.
     */
    private fun resolveAgentId(): Resolution {
        when (
            val r = svid.verify(
                certPem = headers.getHeaderString("X-Agent-Cert"),
                popB64 = headers.getHeaderString("X-Agent-PoP"),
                timestampMillis = headers.getHeaderString("X-Agent-PoP-Ts"),
                nonce = headers.getHeaderString("X-Agent-PoP-Nonce"),
                now = clock.instant(),
            )
        ) {
            is SvidResult.Verified -> {
                // D3b hardening: cross-check the cert CN against the D3a role binding (defense-in-depth).
                // The OpenBao agent-run role already constrains which CNs can be issued (runbook 0007);
                // this second check ensures a misconfigured role or a compromised minter cannot widen
                // privilege beyond what the operator's Keycloak roles allow. Guards the same way as D3a:
                // bypass in %dev/%test (anonymous) or when the binding is disabled.
                if (!identity.isAnonymous && binding.enforced && !binding.permits(identity.roles, r.agentId)) {
                    auditIdentityRejected(
                        reason = "SVID CN not permitted by operator role binding",
                        attemptedAgent = r.agentId,
                        method = "svid_cn_binding",
                        roles = identity.roles,
                    )
                    return Resolution.Rejected
                }
                return Resolution.Allowed(r.agentId)
            }
            is SvidResult.Rejected -> {
                auditIdentityRejected(reason = r.reason, attemptedAgent = null, method = "svid")
                return Resolution.Rejected
            }
            SvidResult.Disabled -> Unit // no SVID configured/presented → fall through
        }
        if (svidEnforced) {
            auditIdentityRejected(reason = "SVID required but none presented", attemptedAgent = null, method = "svid")
            return Resolution.Rejected
        }
        val requested = headers.getHeaderString("X-Agent-Id")?.takeIf { it.isNotBlank() }
        val bound = verifiedAgentId(requested)
        return if (requested != null && bound == null) Resolution.Rejected else Resolution.Allowed(bound)
    }

    /**
     * ADR-0031 D3a: resolve the asserted X-Agent-Id to a *verified* agent identity bound to the
     * operator's roles ([AgentIdentityBinding], deny-by-default). Used as the fallback when no SVID
     * is presented. OIDC is off in %dev/%test (anonymous → legacy header trust); in prod
     * @RolesAllowed guarantees a non-anonymous principal.
     */
    private fun verifiedAgentId(requested: String?): String? {
        if (requested == null) return null
        if (!binding.enforced || identity.isAnonymous) return requested
        if (binding.permits(identity.roles, requested)) return requested
        auditIdentityRejected(
            reason = "not permitted to assume this agent",
            attemptedAgent = requested,
            method = "header_binding",
            roles = identity.roles,
        )
        return null
    }

    /** Single AI-attributed rejection audit for both the SVID and the header-binding paths (D3). */
    private fun auditIdentityRejected(
        reason: String,
        attemptedAgent: String?,
        method: String,
        roles: Set<String>? = null,
    ) {
        val operator = identity.principal?.name?.takeIf { it.isNotBlank() } ?: "unknown"
        log.warnf(
            "D3: operator=%s method=%s identity rejected: %s (attempted=%s)",
            operator.sanitizeForLog(),
            method.sanitizeForLog(),
            reason.sanitizeForLog(),
            attemptedAgent.sanitizeForLog(),
        )
        val payload = buildMap<String, Any?> {
            put("method", method)
            put("reason", reason)
            roles?.let { put("operator_roles", it.sorted()) }
        }
        val event = AuditEvent(
            actorId = operator,
            actorType = "OPERATOR",
            operation = "agent.identity.rejected",
            resourceType = "agent.identity",
            resourceId = attemptedAgent,
            result = AuditResult.DENIED,
            payload = payload,
        )
        runBlocking { auditPublisher.publish(event) }
    }

    /** Best-effort resource id for the audit trail; null when the tool has no resource argument. */
    private fun resourceOf(arguments: JsonNode?): String? = arguments?.let { args ->
        sequenceOf("accountId", "transactionId", "iban")
            .mapNotNull { args.get(it)?.asText()?.takeIf { v -> v.isNotBlank() } }
            .firstOrNull()
    }

    private fun errorResponse(id: JsonNode?, code: Int, message: String): Response =
        Response.ok(McpResponse(id = id, error = McpError(code = code, message = message))).build()
}
