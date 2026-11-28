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
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.McpMetricsPort
import com.openbank.mcp.application.protocol.McpResponse
import com.openbank.mcp.application.protocol.ToolCallResult
import com.openbank.mcp.application.protocol.ToolContent
import com.openbank.mcp.application.protocol.ToolsListResult
import com.openbank.mcp.infrastructure.customer.CustomerChannelAdmission
import com.openbank.mcp.infrastructure.customer.CustomerChannelAdmission.Outcome
import com.openbank.mcp.infrastructure.customer.CustomerGrant
import com.openbank.mcp.infrastructure.customer.CustomerGrantCache
import com.openbank.mcp.infrastructure.customer.CustomerReadTools
import com.openbank.mcp.infrastructure.customer.GrantKey
import com.openbank.mcp.infrastructure.customer.GrantLookupUnavailable
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import jakarta.json.JsonString
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.jwt.JsonWebToken
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID

/**
 * The customer-channel branch of `/mcp` (ADR-0383 D1, ADR-0385 D1), taken only while the channel is
 * enabled. Runs BEFORE the staff audience check and before any tool dispatch:
 * - no token at all → 401 carrying the RFC 9728 `resource_metadata` and the DPoP scheme;
 * - a customers-realm token → admission (aud, allowlisted `azp`, `cnf.jkt`, DPoP proof); a refusal
 *   is answered here and never reaches the staff path;
 * - any other token → `null`, the staff path handles it (and [CallerContextResolver] refuses a
 *   customers-realm token there independently).
 *
 * Admitted calls are bounded by the per-(subject, client) window spent inside admission, BEFORE the
 * DPoP replay claim; an admitted call is therefore audited each time, within that window.
 *
 * An ADMITTED customer sees only the read tools its active `MCP_CLIENT` grants allow (ADR-0383 D3):
 * the grant set is looked up on every call from (party, `azp`, `cnf.jkt`) through
 * [CustomerGrantCache], and every read runs on an RFC 8693-exchanged customer token
 * ([CustomerReadTools], D4). No grant, a failed lookup or a failed exchange is a refusal; the staff
 * tool path is never offered as a substitute.
 */
internal class McpCustomerBranch(
    private val admission: CustomerChannelAdmission,
    private val deps: CustomerChannelDeps,
    /** Shared D5.3 budgets when configured. Otherwise the in-process limiter applies. */
    private val shared: CustomerChannelAdmission.Throttles? = null,
    /** The subject-less window key for this request ([ClientAddressResolver]). */
    private val clientKey: () -> String = { ClientAddressKey.UNKNOWN },
) {
    private val log = Logger.getLogger(McpCustomerBranch::class.java)

    /** Returns null only for an admitted protocol method that continues through MCP dispatch. */
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
        if (!admission.isCustomerToken(jwt)) return refuseNonCustomerToken()
        val outcome = runBlocking { admission.admit(jwt, authorization, dpop, throttles) }
        return when (outcome) {
            is Outcome.Refused -> refuse(outcome, jwt.subject, method, params)
            is Outcome.Admitted -> admitted(outcome, jwt, authorization, dpop.singleOrNull(), method, id, params)
        }
    }

    /** #16847: on the customer root a staff (or any non-customers-realm) token is refused, never dispatched. */
    private fun refuseNonCustomerToken(): Response {
        log.info("customer root refused a token that is not a customers-realm token")
        return Response.status(Response.Status.UNAUTHORIZED)
            .header(WWW_AUTHENTICATE, admission.missingTokenChallenge())
            .build()
    }

    /**
     * ADR-0383 D5.3 budgets — the shared Valkey windows when configured, else the in-process
     * limiter; which run before the DPoP check is decided in admission. Every refusal is metered.
     */
    private val throttles = object : CustomerChannelAdmission.Throttles {
        // With a shared store configured the local limiter is never consulted, not even as a fallback:
        // a shared-store failure throws (503), it does not degrade to per-pod budgets.
        override suspend fun proofKey(thumbprint: String) = metered(
            if (shared != null) {
                shared.proofKey(thumbprint)
            } else {
                reason(deps.limiter.check(CustomerChannelAdmission.proofKeyThrottleKey(thumbprint)))
            },
        )

        override suspend fun client(clientId: String) = metered(
            if (shared != null) {
                shared.client(clientId)
            } else {
                reason(deps.limiter.checkShared(CustomerChannelAdmission.clientThrottleKey(clientId)))
            },
        )

        override suspend fun subject(subject: String, clientId: String) = metered(
            if (shared != null) {
                shared.subject(subject, clientId)
            } else {
                reason(deps.limiter.check(CustomerChannelAdmission.throttleKey(subject, clientId)))
            },
        )
    }

    private fun metered(reason: String?): String? = reason?.also {
        deps.metrics.requestThrottled(
            McpMetricsPort.ThrottleSurface.TOOLS_CALL,
            McpMetricsPort.ThrottleCaller.AUTHENTICATED,
        )
    }

    /**
     * A refusal writes at most ONE audit row per subject per minute (the same window the staff path
     * uses for throttled calls, `McpRateLimiter.shouldAuditThrottle`), so a flood of refused calls —
     * throttled or not — cannot become an unbounded audit-write amplifier. Every refusal is metered.
     */
    private fun refuse(outcome: Outcome.Refused, subject: String?, method: String, params: JsonNode): Response {
        deps.metrics.callerIdentityResolved(CallerIdentitySource.RESOLUTION_FAILED)
        val auditable = deps.limiter.shouldAuditThrottle(customerRefusalWindowKey(subject, clientKey))
        if (auditable) log.warnf("customer channel refused %s: %s", McpInputBounds.logSafe(method), outcome.reason)
        if (auditable && method == TOOLS_CALL && !McpInputBounds.containsNul(params)) {
            audit(UNKNOWN, McpInputBounds.echo(params.path("name").asText("")), params, outcome.reason, emptyList())
        }
        val builder = Response.status(outcome.status)
        outcome.challenge?.let { builder.header(WWW_AUTHENTICATE, it) }
        outcome.resourceNonce?.let { builder.header("DPoP-Nonce", it) }
        return builder.build()
    }

    private sealed interface Grants {
        data class Known(val key: GrantKey, val grants: List<CustomerGrant>) : Grants

        data class Unknown(val reason: String) : Grants
    }

    /**
     * ADR-0383 D3: the grant set for THIS call, from (party of the verified token, admitted `azp`,
     * verified `cnf.jkt`). A missing or malformed `party_id` and a failed lookup are both [Grants.Unknown],
     * which every caller below treats as a refusal.
     */
    private fun grantsFor(outcome: Outcome.Admitted, jwt: JsonWebToken): Grants {
        val claim = when (val raw = jwt.getClaim<Any?>(CLAIM_PARTY_ID)) {
            is String -> raw
            is JsonString -> raw.string
            else -> null
        }
        val party = claim?.takeIf { UUID_SHAPE.matches(it) }?.let(UUID::fromString)
            ?: return Grants.Unknown("token carries no party_id")
        val key = GrantKey(party, outcome.client.clientId, outcome.keyThumbprint, outcome.connectionId)
        return try {
            Grants.Known(key, deps.grantCache.grants(key))
        } catch (e: GrantLookupUnavailable) {
            log.warnf("customer grant lookup unavailable: %s", e.message)
            Grants.Unknown(LOOKUP_UNAVAILABLE)
        }
    }

    @Suppress("LongParameterList", "ReturnCount")
    private fun admitted(
        outcome: Outcome.Admitted,
        jwt: JsonWebToken,
        authorization: String?,
        resourceProof: String?,
        method: String,
        id: JsonNode,
        params: JsonNode,
    ): Response? {
        // initialize / ping / notifications and unknown methods carry no data: the shared dispatch answers them.
        if (method != TOOLS_LIST && method != TOOLS_CALL) return null
        val startedAt = System.nanoTime()
        deps.metrics.callerIdentityResolved(CallerIdentitySource.TOKEN)
        val ctx = outcome.context
        val grants = grantsFor(outcome, jwt)
        if (method == TOOLS_LIST) return list(ctx, grants, id)
        if (McpInputBounds.containsNul(params)) return toolError(id, NOT_PERMITTED)
        val tool = McpInputBounds.echo(params.path("name").asText(""))
        val args = params.path("arguments")
        val known = grants as? Grants.Known ?: run {
            audit(ctx.agentId, tool, params, (grants as Grants.Unknown).reason, ctx.actChain, decision = UNAVAILABLE)
            return toolError(id, AUTHZ_UNAVAILABLE)
        }
        // The subject token of the D4 exchange is the customer's own token — the one admission just
        // matched against the DPoP proof's `ath`. Never any credential of mcp-service's.
        val subjectToken = jwt.rawToken ?: authorization?.substringAfter(DPOP_SCHEME, "").orEmpty()
        val caller = CustomerReadTools.Caller(
            ctx.agentId,
            subjectToken,
            outcome.client.clientId,
            grantKey = known.key,
            sessionId = sessionOf(jwt),
            resourceProof = resourceProof,
        )
        val result = deps.tools.call(tool, args, caller, known.grants)
        val decision = result.auditDecision()
        deps.metrics.toolCallCompleted(
            tool.takeIf { deps.tools.capabilityOf(it) != null } ?: McpMetricsPort.UNMAPPED_TOOL,
            decision,
            if (decision == McpCallAuditor.Decision.ALLOW) AuditResult.SUCCESS else AuditResult.DENIED,
            Duration.ofNanos(System.nanoTime() - startedAt),
            McpMetricsPort.ChannelTags(McpMetricsPort.McpChannel.CUSTOMER, outcome.client.clientId),
        )
        return respond(result, known.key, ctx, tool, params, id)
    }

    @Suppress("LongParameterList")
    private fun respond(
        result: CustomerReadTools.Outcome,
        key: GrantKey,
        ctx: ConsentContext,
        tool: String,
        params: JsonNode,
        id: JsonNode,
    ): Response = when (result) {
        is CustomerReadTools.Outcome.Allowed -> {
            audit(
                ctx.agentId,
                tool,
                params,
                null,
                ctx.actChain,
                decision = McpCallAuditor.Decision.ALLOW,
                capability = result.capability,
                consentId = result.grantId?.let { DELEGATION_PREFIX + it } ?: DELEGATION_PREFIX.trimEnd(':'),
            )
            Response.ok(McpResponse(id = id, result = result.result)).build()
        }
        is CustomerReadTools.Outcome.Refused -> {
            if (result.invalidateGrant) deps.grantCache.invalidate(key)
            val decision = result.auditDecision()
            audit(ctx.agentId, tool, params, result.reason, ctx.actChain, decision, result.capability)
            toolError(id, result.message ?: if (result.unavailable) AUTHZ_UNAVAILABLE else NOT_PERMITTED)
        }
        is CustomerReadTools.Outcome.DownstreamRejected -> {
            // The grant may have been revoked since it was cached: forget it, so the next call asks again.
            deps.grantCache.invalidate(key)
            audit(
                ctx.agentId,
                tool,
                params,
                result.reason,
                ctx.actChain,
                McpCallAuditor.Decision.DENY,
                result.capability,
            )
            toolError(id, NOT_PERMITTED)
        }
    }

    private fun list(ctx: ConsentContext, grants: Grants, id: JsonNode): Response {
        val offered = (grants as? Grants.Known)?.let {
            deps.tools.list(it.grants, it.key.connectionId != null)
        }.orEmpty()
        val reason = when {
            grants is Grants.Unknown -> grants.reason
            offered.isEmpty() -> NO_ACTIVE_GRANT
            else -> null
        }
        runBlocking {
            deps.auditor.toolsListCompleted(
                McpCallAuditor.ToolsList(
                    agentId = ctx.agentId,
                    consentId = NO_CONSENT,
                    toolsReturned = offered.size,
                    toolsTotal = deps.tools.totalTools,
                    pdpErrors = 0,
                    reason = reason,
                    actChain = ctx.actChain,
                ),
            )
        }
        return Response.ok(McpResponse(id = id, result = ToolsListResult(offered))).build()
    }

    private fun toolError(id: JsonNode, text: String): Response = Response.ok(
        McpResponse(id = id, result = ToolCallResult(listOf(ToolContent(text = text)), isError = true)),
    ).build()

    @Suppress("LongParameterList")
    private fun audit(
        agentId: String,
        tool: String,
        params: JsonNode,
        reason: String?,
        actChain: List<String>,
        decision: McpCallAuditor.Decision = McpCallAuditor.Decision.DENY,
        capability: String? = null,
        consentId: String = NO_CONSENT,
    ) = runBlocking {
        deps.auditor.toolCallCompleted(
            McpCallAuditor.ToolCall(
                agentId = agentId,
                consentId = consentId,
                tool = tool,
                capability = capability,
                decision = decision,
                result = if (decision == McpCallAuditor.Decision.ALLOW) AuditResult.SUCCESS else AuditResult.DENIED,
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
        const val TOOLS_LIST = "tools/list"
        const val DPOP_SCHEME = "DPoP "
        const val CLAIM_PARTY_ID = "party_id"
        const val DELEGATION_PREFIX = "delegation:"
        const val NO_ACTIVE_GRANT = "no active MCP_CLIENT grant (ADR-0383 D3)"
        const val LOOKUP_UNAVAILABLE = "delegation grant lookup unavailable"
        const val NOT_PERMITTED = "Not permitted"
        const val AUTHZ_UNAVAILABLE = "Authorization unavailable"
        val UNAVAILABLE = McpCallAuditor.Decision.UNAVAILABLE
        val UUID_SHAPE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

/** Prefix of every customer-channel refusal audit window (`McpRateLimiter.shouldAuditThrottle`). */
internal const val CUSTOMER_REFUSAL_KEY_PREFIX = "customer-refused:"

/**
 * The refusal audit-window key: the token subject when there is one, otherwise the client NETWORK
 * ([ClientAddressKey]: HMAC of IPv4 /32 or IPv6 /64, over the address [ClientAddressResolver] picks,
 * #16847) — never one shared `unknown` key, which would let one tokenless caller's flood suppress
 * every other tokenless caller's audit row. A failing [clientKey] degrades to [ClientAddressKey.UNKNOWN].
 */
internal fun customerRefusalWindowKey(subject: String?, clientKey: () -> String): String =
    CUSTOMER_REFUSAL_KEY_PREFIX + (subject?.let { "sub:$it" } ?: tokenlessRefusalSource(clientKey))

/**
 * The audit-window source of a refusal that carries no token subject: [ClientAddressKey] of the
 * client address, never the raw peer string. Any failure to resolve it (no request in scope, an
 * unreadable connection) keys as [ClientAddressKey.UNKNOWN], never as a fresh per-call key.
 */
internal fun tokenlessRefusalSource(clientKey: () -> String): String =
    runCatching(clientKey).getOrDefault(ClientAddressKey.UNKNOWN)

/** The refusal reason of an in-process [McpRateLimiter] outcome; null when the call is allowed. */
private fun reason(outcome: McpRateLimiter.Outcome): String? =
    outcome.takeUnless { it == McpRateLimiter.Outcome.ALLOWED }?.let { it.reason ?: "rate limit exceeded" }

private fun CustomerReadTools.Outcome.auditDecision(): McpCallAuditor.Decision = when (this) {
    is CustomerReadTools.Outcome.Allowed -> McpCallAuditor.Decision.ALLOW
    is CustomerReadTools.Outcome.Refused -> if (unavailable) {
        McpCallAuditor.Decision.UNAVAILABLE
    } else {
        McpCallAuditor.Decision.DENY
    }
    is CustomerReadTools.Outcome.DownstreamRejected -> McpCallAuditor.Decision.DENY
}

private val SESSION_SHAPE = Regex("^[A-Za-z0-9._:-]{1,128}$")

/** Only a bounded, verified token session may identify a proposal retry. */
private fun sessionOf(jwt: JsonWebToken): String? {
    val session = when (val claim = jwt.getClaim<Any?>("sid")) {
        is String -> claim
        is JsonString -> claim.string
        else -> null
    }
    return session?.takeIf(SESSION_SHAPE::matches)
}
