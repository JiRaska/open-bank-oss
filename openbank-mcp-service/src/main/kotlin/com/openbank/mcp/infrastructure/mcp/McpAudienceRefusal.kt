// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.mcp.application.McpInputBounds
import com.openbank.mcp.application.port.out.CallerIdentitySource
import com.openbank.mcp.application.port.out.McpMetricsPort
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger

/**
 * RFC 8707 / ADR-0383 D1 (#16622): the 401 for a bearer token whose `aud` does not name this
 * server. Runs before the rate limiter, so a refused request spends no window and holds no
 * in-flight slot. A refused `tools/call` is still audited as DENIED.
 */
internal class McpAudienceRefusal(private val throttle: McpThrottleSupport, private val metrics: McpMetricsPort) {
    private val log = Logger.getLogger(McpAudienceRefusal::class.java)

    fun respond(method: String, params: JsonNode, reason: Reason = Reason.FOREIGN_AUDIENCE): Response {
        // The method is caller-supplied and this runs before the NUL guard: bounded and control-free.
        log.warnf("rejected %s: %s", McpInputBounds.logSafe(method), reason.description)
        metrics.callerIdentityResolved(CallerIdentitySource.RESOLUTION_FAILED)
        // Bounded like every other audit write (#16628); a NUL-carrying request is not audited at all.
        // The channel-off refusal answers tokens nobody here can attribute to a subject, so its audit
        // rows share ONE per-minute window: a flood of them cannot become an audit-write amplifier.
        // Only a tools/call is audited, so only a tools/call may spend that window.
        val call = method == "tools/call" && !McpInputBounds.containsNul(params)
        if (call && (reason != Reason.CUSTOMER_CHANNEL_OFF || throttle.shouldAuditRefusal(CHANNEL_OFF_AUDIT_KEY))) {
            throttle.auditAnonymousCall(
                McpInputBounds.echo(params.path("name").asText("")),
                McpInputBounds.auditKeys(params.path("arguments")),
                reason.auditReason,
            )
        }
        return Response.status(Response.Status.UNAUTHORIZED)
            .header("WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"${reason.description}\"")
            .build()
    }

    private companion object {
        const val CHANNEL_OFF_AUDIT_KEY = "customer-channel-off"
    }

    /** Why a presented token is refused before any method runs. Descriptions are constants, never input. */
    enum class Reason(val description: String, val auditReason: String) {
        FOREIGN_AUDIENCE("token audience does not include this server", "token audience mismatch"),

        /** ADR-0383 D1: a customers-realm token while the customer channel is off. */
        CUSTOMER_CHANNEL_OFF("customer channel is not enabled", "customer channel not enabled"),
    }
}
