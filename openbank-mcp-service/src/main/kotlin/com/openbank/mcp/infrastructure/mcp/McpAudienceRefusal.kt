// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.mcp

import com.fasterxml.jackson.databind.JsonNode
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

    fun respond(method: String, params: JsonNode): Response {
        log.warnf("rejected %s: bearer token audience does not include this server", method)
        metrics.callerIdentityResolved(CallerIdentitySource.RESOLUTION_FAILED)
        if (method == "tools/call") {
            throttle.auditAnonymousCall(
                params.path("name").asText(""),
                params.path("arguments").fieldNames().asSequence().sorted().toList(),
                "token audience mismatch",
            )
        }
        return Response.status(Response.Status.UNAUTHORIZED)
            .header("WWW-Authenticate", CHALLENGE)
            .build()
    }

    private companion object {
        const val CHALLENGE =
            "Bearer error=\"invalid_token\", error_description=\"token audience does not include this server\""
    }
}
