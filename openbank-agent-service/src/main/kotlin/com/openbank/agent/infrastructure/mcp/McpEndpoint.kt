// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.agent.infrastructure.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.AgentIdentityBinding
import com.openbank.agent.application.AgentPolicyGate
import com.openbank.agent.application.CharterRegistry
import com.openbank.agent.application.McpToolRegistry
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
        val requested = headers.getHeaderString("X-Agent-Id")?.takeIf { it.isNotBlank() }
        val agentId = verifiedAgentId(requested)
        // ADR-0031 D3: a header that was present but rejected by the identity binding must NOT fall
        // through to the full tool list — advertise nothing. No header at all keeps the legacy
        // full list (the call path still gates every invocation).
        if (requested != null && agentId == null) {
            return ToolsListResult(tools = emptyList())
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

    /**
     * Phase-1 identity (ADR-0031 D9): asserted via the `X-Agent-Id` header; ADR-0031 D3
     * replaces this with a SPIFFE/SPIRE SVID. Absent header -> null -> deny-by-default.
     */
    private fun agentIdentity(arguments: JsonNode?): AgentIdentity? {
        val agentId = verifiedAgentId(headers.getHeaderString("X-Agent-Id")?.takeIf { it.isNotBlank() })
            ?: return null
        return AgentIdentity(
            agentId = agentId,
            plane = headers.getHeaderString("X-Agent-Plane")?.takeIf { it.isNotBlank() },
            skill = arguments?.get("skill")?.asText()?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * ADR-0031 D3: resolve the asserted X-Agent-Id to a *verified* agent identity. The operator is
     * already authenticated (@RolesAllowed); this checks that their verified Keycloak roles
     * authorize assuming the requested agent ([AgentIdentityBinding], deny-by-default). A rejected
     * assertion is audited and returns null (→ deny-by-default at the gate). OIDC is off in
     * %dev/%test (anonymous), where the legacy header trust is preserved so the unit/contract
     * tests are unaffected; in prod @RolesAllowed guarantees a non-anonymous principal.
     */
    private fun verifiedAgentId(requested: String?): String? {
        if (requested == null) return null
        if (!binding.enforced || identity.isAnonymous) return requested
        if (binding.permits(identity.roles, requested)) return requested
        auditRejectedAssumption(requested, identity.roles)
        return null
    }

    private fun auditRejectedAssumption(requested: String, roles: Set<String>) {
        val operator = identity.principal?.name?.takeIf { it.isNotBlank() } ?: "unknown"
        log.warnf(
            "D3: operator=%s roles=%s attempted to assume agent '%s' — not permitted, rejected",
            operator,
            roles,
            requested,
        )
        val event = AuditEvent(
            actorId = operator,
            actorType = "OPERATOR",
            operation = "agent.identity.rejected",
            resourceType = "agent.identity",
            resourceId = requested,
            result = AuditResult.DENIED,
            payload = mapOf("attempted_agent" to requested, "operator_roles" to roles.sorted()),
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
