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
import com.openbank.mcp.application.McpInputBounds
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
import com.openbank.mcp.infrastructure.customer.CustomerChannel
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import io.vertx.core.http.HttpServerRequest
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
 * Policy-input path (ADR-0195 step 5, #3292): `rest.rego`'s `agent-charter-allows` bridge forwards
 * the `attributes` map to `agents.allow`, so the shared PDP sees the `consentId` sent above on every
 * call — the capability-vs-charter decision is real, and a future policy can now gate on consent
 * state (threat model §3, docs/threat-models/openbank-mcp-service.md). Consent enforcement today
 * still lives in the read port (live validation at consent-service); the policy plane merely has
 * the input it needs to add a second layer.
 */
@Path("/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
// Two explicit entries (staff `handle`, customer `handleCustomer`) are the review-of-#17004 fix: the
// branch is the matched resource, not a re-read path. Splitting the class would move that decision away.
@Suppress("TooManyFunctions")
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

    /**
     * Field-injected for the same reason as [metrics]. The customer MCP channel (ADR-0383/0385);
     * off unless `mcp.customer.enabled`. Plain unit tests that construct this class leave it unset,
     * which reads as "off".
     */
    @Inject
    lateinit var customerChannel: CustomerChannel

    /** Field-injected for the same reason as [metrics]: the subject-less audit key (#16847). */
    @Inject
    lateinit var clientAddress: ClientAddressResolver

    /** The current request (a request-scoped proxy) — `Authorization` scheme and `DPoP` proof. */
    @Inject
    lateinit var httpRequest: HttpServerRequest

    private val log = Logger.getLogger(McpEndpoint::class.java)

    private val throttle by lazy { McpThrottleSupport(auditor, metrics, rateLimiter) }

    private val audienceRefusal by lazy { McpAudienceRefusal(throttle, metrics) }

    private val customerBranch by lazy {
        if (!::customerChannel.isInitialized || !::httpRequest.isInitialized) return@lazy null
        customerChannel.admission?.let { admission ->
            McpCustomerBranch(admission, auditor, metrics, rateLimiter) {
                if (::clientAddress.isInitialized) clientAddress.key(httpRequest) else ClientAddressKey.UNKNOWN
            }
        }
    }

    /**
     * Refusals decided before any method runs, in order: the customer-channel branch (ADR-0383 D1 /
     * ADR-0385 D1), then the RFC 8707 audience refusal (#16622). Null means dispatch normally.
     *
     * #16847 (owner decision 2026-11-21): the customer channel has its OWN root, [CUSTOMER_ROOT]
     * ([CustomerMcpEndpoint], DPoP-only, 8443-only). The branch is chosen by WHICH RESOURCE matched
     * ([handleCustomer] vs [handle]), never by re-reading the request path: a raw-path comparison let
     * `/customer/mcp;a=b` or `/customer/%6dcp` reach [CustomerMcpEndpoint] and take the staff dispatch
     * (review of #17004). [McpPathCanonicalFilter] refuses such paths outright. The branch runs only there, and there a token that
     * is not a customers-realm token is refused rather than handed to the staff path. On `/mcp` the
     * branch never runs; [CallerContextResolver] refuses a customers-realm token on the staff path.
     */
    private fun preDispatch(customer: Boolean, method: String, id: JsonNode, params: JsonNode): Response? =
        if (customer) {
            customerBranch?.let { branch ->
                branch.handle(
                    callerResolver.token,
                    httpRequest.getHeader(AUTHORIZATION_HEADER),
                    httpRequest.headers().getAll(DPOP_HEADER).orEmpty(),
                    method,
                    id,
                    params,
                ) ?: branch.refuseNonCustomerToken()
            } ?: Response.status(Response.Status.NOT_FOUND).build()
        } else {
            // #16590 defence in depth: a customer credential (customers-realm `iss`, or the DPoP scheme
            // no staff caller uses) is refused on the staff root WHATEVER the channel state, before the
            // audience check. The resolver would only ever treat it as anonymous; refusing it here means
            // no staff dispatch path ever runs for it, and the client is told where it belongs.
            audienceRefusal.takeIf { presentsCustomerCredential() }?.respondCustomerOnStaffRoot(
                method,
                params,
                if (customerBranch == null) {
                    McpMetricsPort.CustomerRejectReason.CUSTOMER_CHANNEL_OFF
                } else {
                    McpMetricsPort.CustomerRejectReason.CUSTOMER_ROOT_REQUIRED
                },
                callerResolver.token.getClaim<String?>("azp"),
            ) ?: audienceRefusal.takeIf { callerResolver.presentsForeignAudience() }?.respond(method, params)
        }

    private fun presentsCustomerCredential(): Boolean = callerResolver.presentsCustomerRealmToken() ||
        (
            ::httpRequest.isInitialized &&
                httpRequest.getHeader(AUTHORIZATION_HEADER)
                    ?.trimStart()?.startsWith("$DPOP_HEADER ", ignoreCase = true) == true
            )

    /** The staff root. Never runs the customer branch, whatever the request path says. */
    @POST
    fun handle(body: JsonNode): Response = dispatch(body, customer = false)

    /** The customer root's entry, called only by [CustomerMcpEndpoint]; never runs the staff audience path. */
    fun handleCustomer(body: JsonNode): Response = dispatch(body, customer = true)

    @Suppress("ReturnCount")
    private fun dispatch(body: JsonNode, customer: Boolean): Response {
        val id = body.path("id").let { if (it.isMissingNode) NullNode.instance else it }
        val method = body.path("method").asText("")
        val params = body.path("params")

        // Bounded before it becomes a tag: `method` is a caller-supplied string on a public agent
        // surface, so an unrecognised value must not mint a new metric series (cardinality contract).
        metrics.requestHandled(if (method in KNOWN_METHODS) method else McpMetricsPort.UNKNOWN_METHOD)

        // Fleet NUL rule (#5913, #16628): refused before the audience check and the customer branch, so
        // no tool, consent call or audit write ever sees the raw value. The id is echoed only if it
        // is NUL-free.
        if (McpInputBounds.containsNul(body)) {
            log.warn("refused JSON-RPC request containing U+0000")
            metrics.inputRefused(McpMetricsPort.InputRefusal.NUL)
            val safeId = body.path("id").takeUnless { it.isMissingNode || McpInputBounds.containsNul(it) }
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(
                    McpResponse(
                        id = safeId ?: NullNode.instance,
                        error = McpError(McpErrorCode.INVALID_PARAMS, NUL_REFUSED),
                    ),
                )
                .build()
        }

        // ADR-0383 D1 / ADR-0385 D1: while the customer channel is on, a call with no token gets the
        // RFC 9728 401 and a customers-realm token is admitted (or refused) on its own branch — it
        // never reaches the staff audience check, the agent/OBO resolver, or a staff tool.
        // RFC 8707 / ADR-0383 D1 (#16622): a token issued for another resource is refused before
        // any method runs, on every method. The tenant-wide `quarkus.oidc.token.audience` cannot do
        // this, because /api/v1/mcp/sessions is called with the operator's own token, whose
        // audience is not this server.
        // The fleet NUL guard above runs FIRST, for both paths: no branch, refusal or audit sees a
        // raw U+0000, and no response echoes one (review of #16721).
        preDispatch(customer, method, id, params)?.let { return it }

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
            else -> return error(id, McpErrorCode.METHOD_NOT_FOUND, "Method not found: ${McpInputBounds.echo(method)}")
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
        // Throttle BEFORE any PDP call (ADR-0383 D5).
        throttle.toolsListRefusal(ctx, registry.tools.size)?.let {
            return error(id, RATE_LIMITED, "Rate limit exceeded: $it")
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
                principal = Principal(
                    id = ctx.agentId,
                    type = ctx.principalType,
                    roles = ctx.roles,
                    serviceAccount = ctx.serviceAccount,
                ),
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

    @Suppress("ReturnCount")
    private fun handleToolCall(id: JsonNode, params: JsonNode): Response {
        val startedAt = System.nanoTime()
        // Bounded for every reflection below — response text, audit record and log line (#16628).
        val toolName = McpInputBounds.echo(params.path("name").asText(""))
        val arguments = params.path("arguments").let { if (it.isMissingNode) mapper.createObjectNode() else it }
        // Argument KEY names only — the values are customer data and must never enter the audit
        // trail (McpCallAuditor KDoc); count and length are bounded (#16628).
        val argumentKeys = McpInputBounds.auditKeys(arguments)
        // Bounded before it becomes a tag: an unmapped tool name is caller-supplied, so it is
        // reported as the single "unmapped" value rather than as itself (cardinality contract). The
        // audit event still carries the exact name — that is a per-call record, not a label.
        val toolTag = if (registry.capabilities.containsKey(toolName)) toolName else McpMetricsPort.UNMAPPED_TOOL

        // Caller authentication (ADR-0195): the acting agent + presented consent come from the
        // validated OAuth token. A malformed agent token (e.g. no consent_id) fails CLOSED — a
        // money-adjacent surface never degrades to an unscoped identity.
        val ctx = resolveCaller().getOrElse { return unauthenticated(id, toolName, argumentKeys, it) }

        // Audit and meter together, at every terminal branch, so the aggregate can never disagree
        // with the per-call trail about what happened.
        fun audit(
            capability: String?,
            decision: McpCallAuditor.Decision,
            result: AuditResult,
            reason: String? = null,
            persist: Boolean = true,
        ) = runBlocking {
            if (persist) {
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
            }
            metrics.toolCallCompleted(
                toolTag,
                decision,
                result,
                Duration.ofNanos(System.nanoTime() - startedAt),
                // ADR-0384 D1: the channel the resolver's admitting branch recorded, never a claim.
                McpMetricsPort.ChannelTags(ctx.channel),
            )
        }

        // Throttle BEFORE any policy call or tool execution (ADR-0383 D5): a denied flood must not
        // cost one PDP round trip per request. Every throttled request is metered; the audit trail
        // keeps one row per caller per window (McpRateLimiter.shouldAuditThrottle), so a flood
        // cannot become an unbounded audit-write amplifier.
        val capability = registry.capabilities[toolName]
        val throttleReason = throttle.refusalReason(ctx.agentId)
        if (throttleReason != null) {
            val persist = throttle.callRefused(ctx.agentId)
            audit(capability, McpCallAuditor.Decision.DENY, AuditResult.DENIED, throttleReason, persist)
            return toolError(id, "Rate limit exceeded: $throttleReason")
        }
        try {
            return authorizeAndExecute(
                CallScope(id, toolName, arguments, ctx, capability) { c, d, r, why ->
                    audit(c, d, r, why)
                },
            )
        } finally {
            rateLimiter.release(ctx.agentId)
        }
    }

    /**
     * No resolvable caller: bounded by the shared anonymous bucket BEFORE any audit or identity
     * meter is written, so an unauthenticated flood costs neither. Within budget it is denied (and
     * audited) as before.
     */
    private fun unauthenticated(id: JsonNode, toolName: String, keys: List<String>, cause: Throwable): Response {
        throttle.anonymousCallRefusal(toolName, keys)?.let { return toolError(id, "Rate limit exceeded: $it") }
        log.warnf("caller context resolution failed for %s: %s — denying", toolName, cause.message)
        metrics.callerIdentityResolved(CallerIdentitySource.RESOLUTION_FAILED)
        throttle.auditAnonymousCall(toolName, keys, "caller authentication failed")
        return toolError(id, "Authorization unavailable")
    }

    /** Everything the PDP gate and tool run need for one admitted call, in one parameter. */
    private class CallScope(
        val id: JsonNode,
        val toolName: String,
        val arguments: JsonNode,
        val ctx: ConsentContext,
        val capability: String?,
        val audit: (String?, McpCallAuditor.Decision, AuditResult, String?) -> Unit,
    )

    /** The PDP gate and the tool run; only reached once the caller is inside its throttle budget. */
    // ReturnCount: each early return is one deny-by-default gate (unmapped, PDP outage, policy deny);
    // TooGenericExceptionCaught: a PDP transport error of any type must fail closed.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun authorizeAndExecute(scope: CallScope): Response {
        val id = scope.id
        val toolName = scope.toolName
        val arguments = scope.arguments
        val ctx = scope.ctx
        val capability = scope.capability
        val audit = scope.audit
        // Deny-by-default: no capability mapping ⇒ no OPA action ⇒ refuse.
        if (capability == null) {
            audit(null, McpCallAuditor.Decision.DENY, AuditResult.DENIED, "no capability mapping")
            return toolError(id, "Tool not permitted: ${McpInputBounds.echo(toolName)}")
        }

        // Gate on the SHARED ADR-0034 PDP (input.action = the capability). The principal class
        // follows the token: AI_AGENT for consent-bound agent tokens, HUMAN with its bounded realm
        // roles for an OBO staff token (ADR-0224) — the same role vocabulary a REST call presents.
        val decision = try {
            runBlocking {
                pdp.allow(
                    AuthzQuery(
                        principal = Principal(
                            id = ctx.agentId,
                            type = ctx.principalType,
                            roles = ctx.roles,
                            serviceAccount = ctx.serviceAccount,
                        ),
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

        return execute(id, toolName, arguments, ctx) { result, reason ->
            audit(capability, McpCallAuditor.Decision.ALLOW, result, reason)
        }
    }

    /**
     * Run one authorized tool and turn its outcome into a JSON-RPC response, auditing through
     * [record]. Extracted from [handleToolCall] to keep that method under detekt's cyclomatic
     * threshold — the branching here is the tool-failure taxonomy, which is a separate concern from
     * the authorization sequence above it.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun execute(
        id: JsonNode,
        toolName: String,
        arguments: JsonNode,
        ctx: ConsentContext,
        record: (AuditResult, String?) -> Unit,
    ): Response = try {
        val result = registry.call(toolName, arguments, ctx)
        record(AuditResult.SUCCESS, null)
        Response.ok(McpResponse(id = id, result = result)).build()
    } catch (ex: IllegalArgumentException) {
        record(AuditResult.FAILURE, "invalid params")
        error(id, McpErrorCode.INVALID_PARAMS, ex.message ?: "invalid params")
    } catch (ex: UnsupportedOperationException) {
        // A tool that is advertised but has nothing behind it (today: propose_payment, see
        // UnwiredProposalPort, #2414). Its reason is relayed VERBATIM — unlike the opaque "tool
        // error" below — because the caller is a model that will otherwise narrate the silence as a
        // success to a person. The message is a constant in the port and carries no customer data;
        // a tool must not put anything caller-specific in it.
        record(AuditResult.FAILURE, "tool unavailable")
        toolError(id, ex.message ?: "Tool unavailable")
    } catch (ex: Exception) {
        log.warnf("tool %s failed: %s", toolName, ex.message)
        record(AuditResult.FAILURE, "tool error")
        Response.ok(
            McpResponse(id = id, result = ToolCallResult(listOf(ToolContent(text = "tool error")), isError = true)),
        ).build()
    }

    // Resolve the caller's identity + consent; a failure is returned (not audited here) so the caller
    // can spend the anonymous throttle BEFORE any audit or meter is written. Fails closed on any error.
    @Suppress("TooGenericExceptionCaught")
    private fun resolveCaller(): Result<ConsentContext> = try {
        Result.success(resolveContext())
    } catch (ex: Exception) {
        Result.failure(ex)
    }

    // ADR-0195 step 4: the phase-1 placeholder identity is REMOVED. The acting agent + consent come
    // ONLY from a validated OAuth token (CallerContextResolver) — no agent token is treated the same
    // as a malformed one: unauthenticated() denies, audits, and meters
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

    companion object {
        /** The customer channel's own path root (#16847); literal in [CustomerMcpEndpoint]'s `@Path`. */
        const val CUSTOMER_ROOT = "/customer/mcp"

        /** JSON-RPC implementation-defined server error range (-32000..-32099): request throttled. */
        private const val RATE_LIMITED = -32029

        private const val DPOP_HEADER = "DPoP"
        private const val AUTHORIZATION_HEADER = "Authorization"

        private const val NUL_REFUSED = "Invalid params: U+0000 is not permitted in any string"

        /**
         * The JSON-RPC methods this server implements. Used ONLY to bound the `method` metric tag —
         * the dispatch itself is the `when` in [handle], which stays the single source of behaviour.
         */
        private val KNOWN_METHODS = setOf("initialize", "notifications/initialized", "ping", "tools/list", "tools/call")
    }
}
