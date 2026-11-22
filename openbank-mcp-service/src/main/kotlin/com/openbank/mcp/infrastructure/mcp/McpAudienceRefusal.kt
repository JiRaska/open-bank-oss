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

    fun respond(method: String, params: JsonNode): Response {
        // The method is caller-supplied and this runs before the NUL guard: bounded and control-free.
        log.warnf("rejected %s: bearer token audience does not include this server", McpInputBounds.logSafe(method))
        metrics.callerIdentityResolved(CallerIdentitySource.RESOLUTION_FAILED)
        // Bounded like every other audit write (#16628); a NUL-carrying request is not audited at all.
        if (method == "tools/call" && !McpInputBounds.containsNul(params)) {
            throttle.auditAnonymousCall(
                McpInputBounds.echo(params.path("name").asText("")),
                McpInputBounds.auditKeys(params.path("arguments")),
                "token audience mismatch",
            )
        }
        return Response.status(Response.Status.UNAUTHORIZED)
            .header("WWW-Authenticate", CHALLENGE)
            .build()
    }

    /**
     * #16590: the 401 for a customer credential on the STAFF root — refused whatever the channel
     * state, so no staff dispatch path ever runs for it. Counted on the customer-rejected series
     * (`layer="token"`) and, for `tools/call`, audited as DENIED like the audience refusal.
     */
    fun respondCustomerOnStaffRoot(
        method: String,
        params: JsonNode,
        reason: McpMetricsPort.CustomerRejectReason,
        client: String?,
    ): Response {
        log.warnf(
            "rejected %s on the staff root: customer credential (%s)",
            McpInputBounds.logSafe(method),
            reason.name.lowercase(),
        )
        metrics.customerRejected(reason, client, synthetic = false)
        // The credential is unverified here (a junk `DPoP x` reaches this line), so the audit write is
        // bounded by the shared anonymous bucket FIRST: beyond it, only the throttle's once-per-window
        // row is written, never one per request (review of #17086).
        if (method == "tools/call" && !McpInputBounds.containsNul(params)) {
            val tool = McpInputBounds.echo(params.path("name").asText(""))
            val keys = McpInputBounds.auditKeys(params.path("arguments"))
            if (throttle.anonymousCallRefusal(tool, keys) == null) {
                throttle.auditAnonymousCall(tool, keys, "customer credential on the staff root")
            }
        }
        return Response.status(Response.Status.UNAUTHORIZED)
            .header("WWW-Authenticate", CUSTOMER_CHALLENGE)
            .build()
    }

    /** More than one `Authorization` header: which credential the OIDC layer validated is ambiguous. */
    fun respondAmbiguousCredential(method: String): Response {
        log.warnf("rejected %s on the staff root: more than one Authorization header", McpInputBounds.logSafe(method))
        metrics.callerIdentityResolved(CallerIdentitySource.RESOLUTION_FAILED)
        return Response.status(Response.Status.BAD_REQUEST)
            .header("WWW-Authenticate", AMBIGUOUS_CHALLENGE)
            .build()
    }

    /** The `Authorization` header could not be read: refused, never served as anonymous (fail closed). */
    fun respondUnreadableCredential(method: String): Response {
        log.warnf("rejected %s on the staff root: Authorization header unreadable", McpInputBounds.logSafe(method))
        metrics.callerIdentityResolved(CallerIdentitySource.RESOLUTION_FAILED)
        return Response.status(Response.Status.BAD_REQUEST)
            .header("WWW-Authenticate", UNREADABLE_CHALLENGE)
            .build()
    }

    /** An `Authorization` value outside the staff root's `Bearer <b64token>` allowlist. */
    fun respondMalformedCredential(method: String): Response {
        log.warnf("rejected %s on the staff root: malformed Authorization header", McpInputBounds.logSafe(method))
        metrics.callerIdentityResolved(CallerIdentitySource.RESOLUTION_FAILED)
        return Response.status(Response.Status.BAD_REQUEST)
            .header("WWW-Authenticate", MALFORMED_CHALLENGE)
            .build()
    }

    private companion object {
        const val MALFORMED_CHALLENGE =
            "Bearer error=\"invalid_request\", error_description=\"Authorization must be one Bearer b64token\""
        const val UNREADABLE_CHALLENGE =
            "Bearer error=\"invalid_request\", error_description=\"the Authorization header could not be read\""
        const val AMBIGUOUS_CHALLENGE =
            "Bearer error=\"invalid_request\", error_description=\"exactly one Authorization header is allowed\""
        const val CUSTOMER_CHALLENGE =
            "Bearer error=\"invalid_token\", error_description=\"customer credentials are accepted only at " +
                McpEndpoint.CUSTOMER_ROOT + "\""
        const val CHALLENGE =
            "Bearer error=\"invalid_token\", error_description=\"token audience does not include this server\""
    }
}
