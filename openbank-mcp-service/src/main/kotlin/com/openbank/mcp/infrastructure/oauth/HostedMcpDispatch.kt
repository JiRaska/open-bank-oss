// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.mcp.application.McpInputBounds
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import jakarta.ws.rs.core.Response.Status
import jakarta.ws.rs.core.Response.Status.FORBIDDEN
import jakarta.ws.rs.core.Response.Status.UNAUTHORIZED
import org.jboss.logging.Logger

internal fun interface HostedBankDpopBinding {
    fun thumbprint(vendor: Vendor): String?
}

/** Forwards only through the existing customer MCP DPoP authorization and mTLS boundaries. */
internal fun interface HostedBankMcpUpstream {
    suspend fun dispatch(vendor: Vendor, bankAccessToken: String, request: JsonNode, protocolVersion: String?): Reply

    class Reply(val status: Int, val body: JsonNode?)
}

/** Scope attenuation in both directions, before a tool can reach the bank's customer channel. */
internal class HostedMcpDispatch(
    private val grants: HostedVendorGrants,
    private val bankAccess: HostedBankAccess,
    private val upstream: HostedBankMcpUpstream,
) {
    sealed interface Outcome {
        class Forwarded(val reply: HostedBankMcpUpstream.Reply) : Outcome
        class Unsupported(val id: JsonNode) : Outcome
        data object NotificationAccepted : Outcome

        enum class Refused : Outcome { INVALID_TOKEN, INVALID_REQUEST, OUTSIDE_SCOPE, BANK_UNAVAILABLE }
    }

    suspend fun dispatch(accessToken: String, request: JsonNode, protocolVersion: String? = null): Outcome {
        if (protocolVersion != null &&
            !PROTOCOL_VERSION.matches(protocolVersion)
        ) {
            return Outcome.Refused.INVALID_REQUEST
        }
        val identity = grants.admit(accessToken) ?: return Outcome.Refused.INVALID_TOKEN
        if (!request.isObject || request.path("jsonrpc").textValue() != "2.0") return Outcome.Refused.INVALID_REQUEST
        val method = request.path("method").textValue() ?: return Outcome.Refused.INVALID_REQUEST
        if (method.startsWith("notifications/") && request.has("id")) return Outcome.Refused.INVALID_REQUEST
        if (!allowed(method, request)) return refuseMethod(method, request)
        val bank = bankAccess.load(identity.vendor, identity.connectionId)
            ?: return Outcome.Refused.INVALID_TOKEN
        val response = upstream.dispatch(identity.vendor, bank.access, request, protocolVersion)
        return projectReply(method, response)
    }

    private fun projectReply(method: String, response: HostedBankMcpUpstream.Reply): Outcome {
        if (response.status == UNAUTHORIZED.statusCode || response.status == FORBIDDEN.statusCode) {
            return Outcome.Refused.INVALID_TOKEN
        }
        if (Status.Family.familyOf(response.status) != Status.Family.SUCCESSFUL) return Outcome.Refused.BANK_UNAVAILABLE
        return when (method) {
            "initialize" -> hostedCapabilities(response)
            "notifications/initialized" -> Outcome.NotificationAccepted
            "tools/list" -> filtered(response)
            else -> Outcome.Forwarded(response)
        }
    }

    private companion object {
        val PROTOCOL_VERSION = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        val log = Logger.getLogger(HostedMcpDispatch::class.java)
    }

    private fun allowed(method: String, request: JsonNode): Boolean = when (method) {
        "initialize", "notifications/initialized", "ping", "tools/list" -> true
        "tools/call" -> request.path("params").path("name").textValue()?.let(HostedVendorGrants::allowsTool) == true
        else -> false
    }

    private fun refuseMethod(method: String, request: JsonNode): Outcome {
        if (method == "tools/call") {
            log.info("Hosted MCP refused category=TOOL_OUTSIDE_SCOPE")
            return Outcome.Refused.OUTSIDE_SCOPE
        }
        val category = when (method) {
            "resources/list", "resources/templates/list", "resources/read" -> "RESOURCE_RPC"
            "prompts/list", "prompts/get" -> "PROMPT_RPC"
            else -> "OTHER_RPC"
        }
        log.infof("Hosted MCP refused category=%s", category)
        if (McpInputBounds.containsNul(request) || request.get("params")?.isContainerNode == false) {
            return Outcome.Refused.INVALID_REQUEST
        }
        val id = request.get("id") ?: return Outcome.NotificationAccepted
        if (!id.isTextual && !id.isIntegralNumber) {
            return Outcome.Refused.INVALID_REQUEST
        }
        return Outcome.Unsupported(id)
    }

    private fun hostedCapabilities(reply: HostedBankMcpUpstream.Reply): Outcome {
        val body = (reply.body as? ObjectNode)?.deepCopy() ?: return Outcome.Refused.BANK_UNAVAILABLE
        if (body.has("error")) return Outcome.Forwarded(reply)
        val capabilities = body.path("result").path("capabilities") as? ObjectNode
            ?: return Outcome.Refused.BANK_UNAVAILABLE
        capabilities.retain("tools")
        return Outcome.Forwarded(HostedBankMcpUpstream.Reply(reply.status, body))
    }

    private fun filtered(reply: HostedBankMcpUpstream.Reply): Outcome {
        val body = (reply.body as? ObjectNode)?.deepCopy() ?: return Outcome.Refused.BANK_UNAVAILABLE
        if (body.has("error")) return Outcome.Forwarded(reply)
        val result = body.path("result") as? ObjectNode ?: return Outcome.Refused.BANK_UNAVAILABLE
        val list = result.path("tools").takeIf(JsonNode::isArray) ?: return Outcome.Refused.BANK_UNAVAILABLE
        val allowed = result.arrayNode()
        list.filter { it.path("name").textValue()?.let(HostedVendorGrants::allowsTool) == true }.forEach(allowed::add)
        result.set<JsonNode>("tools", allowed)
        return Outcome.Forwarded(HostedBankMcpUpstream.Reply(reply.status, body))
    }
}
