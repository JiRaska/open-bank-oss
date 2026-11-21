// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application.port.out

import com.openbank.libs.audit.AuditResult
import com.openbank.mcp.application.McpCallAuditor
import java.time.Duration

/**
 * Outbound observability port (ADR-0002 / ADR-0077 Tier C) for the MCP server (ADR-0181).
 *
 * This is the bank's AI-agent surface: every `tools/call` is an AI-initiated action against customer
 * data or (for `propose_payment`) the payment path. The audit trail already records **what** each
 * call did, one event at a time; these meters answer the questions an audit trail cannot — the rate,
 * the outcome mix, and the latency — without querying the audit store:
 *
 *  - `decision=UNAVAILABLE` means the PDP could not be reached and **every** agent call is being
 *    denied, fail-closed. Correct, and previously nothing but a WARN line.
 *  - `tool="unmapped"` is the deny-by-default gate firing: an agent asking for a tool that has no
 *    capability entry. One is a client bug; a stream of them is enumeration of the tool surface.
 *  - [callerIdentityResolved] measures how many calls still run under the **phase-1 placeholder
 *    identity** (`agent:mcp-anonymous`) rather than a validated OAuth token. That is blocker #2206
 *    expressed as a number instead of a code comment: it must reach zero before a real read port
 *    replaces the stub.
 *
 * Kept as a port rather than a direct `MeterRegistry` dependency so the endpoint stays testable
 * without Micrometer, and so the counters are exercised through the real adapter (over a
 * `SimpleMeterRegistry`) in unit tests.
 *
 * **Cardinality:** both the JSON-RPC `method` and the `tool` name are caller-supplied strings on a
 * public agent surface. The endpoint maps each to a bounded value before it reaches this port — an
 * unrecognised method becomes [UNKNOWN_METHOD] and an unmapped tool becomes [UNMAPPED_TOOL] — so a
 * client cannot mint unbounded metric series by inventing names.
 *
 * Implemented by [com.openbank.mcp.infrastructure.observability.McpMetricsAdapter].
 */
interface McpMetricsPort {

    /** Record one JSON-RPC request, by (bounded) method name. */
    fun requestHandled(method: String)

    /**
     * Record one completed `tools/call`, mirroring the tags of its audit event. [channel] adds the
     * ADR-0384 D1 `channel`, `client` and `synthetic` tags; it defaults to the agent channel with
     * the fixed placeholders, because Prometheus accepts one tag-key set per meter name and every
     * channel must therefore carry the same keys.
     */
    fun toolCallCompleted(
        tool: String,
        decision: McpCallAuditor.Decision,
        result: AuditResult,
        duration: Duration,
        channel: ChannelTags = ChannelTags.AGENT,
    )

    /**
     * Record one customer-channel request refused before it reached a tool (ADR-0384 D1). The
     * reason carries its layer, so an impossible (layer, reason) pair cannot be expressed. Counts a
     * REFUSAL observed by this server — never a judgement about the caller. Default no-op so test
     * doubles need not implement it.
     */
    fun customerRejected(reason: CustomerRejectReason, client: String?, synthetic: Boolean) = Unit

    /** Record how the acting agent's identity was established for one `tools/call`. */
    fun callerIdentityResolved(source: CallerIdentitySource)

    /** Record one `tools/list` discovery, by outcome (ADR-0225 D4 — discovery recon is countable). */
    fun toolsListCompleted(outcome: ToolsListOutcome)

    /**
     * Record one request refused by the throttle (rate window or in-flight cap). Metered per request
     * even where the audit trail is sampled to one row per window, so the aggregate stays exact.
     */
    fun requestThrottled(surface: ThrottleSurface, caller: ThrottleCaller)

    /**
     * Record one request refused by an input bound before dispatch (#16628). Default no-op so test
     * doubles need not implement it; the Micrometer adapter overrides it.
     */
    fun inputRefused(reason: InputRefusal) = Unit

    /**
     * Record one refused call whose audit row was SUPPRESSED by the per-source audit window, so a
     * sampled audit trail still has an exact count beside it. Default no-op for test doubles.
     */
    fun auditSuppressed(reason: AuditSuppression) = Unit

    /** Why an audit row was suppressed. A closed enum, so safe as a tag. */
    enum class AuditSuppression { CUSTOMER_CHANNEL_OFF, }

    /** Which MCP channel a call arrived on (ADR-0384 D1), derived from the resolution path, never a client claim. */
    enum class McpChannel { AGENT, OPERATOR, CUSTOMER }

    /**
     * The channel tags of one call. [client] is the RAW `azp` for a customer call; the adapter maps
     * it through the allowlist to a bounded value (`unlisted` for anything else), so a caller can
     * never mint a series. Agent and operator calls carry the placeholders `none` / `false`.
     */
    data class ChannelTags(val channel: McpChannel, val client: String? = null, val synthetic: Boolean = false) {
        companion object {
            val AGENT = ChannelTags(McpChannel.AGENT)
            val OPERATOR = ChannelTags(McpChannel.OPERATOR)
        }
    }

    /** Where in the customer admission path a request was refused (ADR-0384 D1). Closed set. */
    enum class CustomerRejectLayer { EDGE_UNAUTH, TOKEN, DPOP, GRANT, THROTTLE, POLICY, DOWNSTREAM }

    /** Why a customer request was refused. Each reason belongs to exactly one layer (closed enum, safe as a tag). */
    enum class CustomerRejectReason(val layer: CustomerRejectLayer) {
        UNAUTHENTICATED(CustomerRejectLayer.EDGE_UNAUTH),
        REVOKED_GRANT(CustomerRejectLayer.TOKEN),
        EXPIRED(CustomerRejectLayer.TOKEN),
        WRONG_AUDIENCE(CustomerRejectLayer.TOKEN),
        MISSING(CustomerRejectLayer.DPOP),
        BAD_SIG(CustomerRejectLayer.DPOP),
        HTU_MISMATCH(CustomerRejectLayer.DPOP),
        ATH_MISMATCH(CustomerRejectLayer.DPOP),
        STALE_IAT(CustomerRejectLayer.DPOP),
        REPLAY(CustomerRejectLayer.DPOP),
        JKT_MISMATCH(CustomerRejectLayer.DPOP),
        NONCE_REQUIRED(CustomerRejectLayer.DPOP),
        REPLAY_STORE_UNAVAILABLE(CustomerRejectLayer.DPOP),
        NO_GRANT(CustomerRejectLayer.GRANT),
        CLIENT_SUSPENDED(CustomerRejectLayer.GRANT),
        UNAUTH_BUCKET(CustomerRejectLayer.THROTTLE),
        PARTY_CLIENT_BUCKET(CustomerRejectLayer.THROTTLE),
        PARTY_DAILY(CustomerRejectLayer.THROTTLE),
        CONCURRENCY(CustomerRejectLayer.THROTTLE),
        DOWNSTREAM_BUDGET(CustomerRejectLayer.THROTTLE),
        CIRCUIT_OPEN(CustomerRejectLayer.THROTTLE),
        POLICY_DENY(CustomerRejectLayer.POLICY),
        POLICY_UNAVAILABLE(CustomerRejectLayer.POLICY),
        DOWNSTREAM_ERROR(CustomerRejectLayer.DOWNSTREAM),
    }

    /** Why a request was refused before dispatch. A closed enum, so safe as a tag. */
    enum class InputRefusal { NUL, }

    /** Which JSON-RPC surface was throttled. A closed enum, so safe as a tag. */
    enum class ThrottleSurface { TOOLS_CALL, TOOLS_LIST }

    /** Whether the throttled request carried a resolvable caller identity. */
    enum class ThrottleCaller { AUTHENTICATED, ANONYMOUS }

    /** Bounded `tools/list` outcome set — a closed enum, so it is safe as a metric tag. */
    enum class ToolsListOutcome {
        /** Discovery completed (possibly filtered to a subset, or empty on a policy deny). */
        OK,

        /** No/malformed caller token — empty list returned, fail-closed like the call path. */
        ANONYMOUS_DENIED,

        /** Every capability evaluation failed on PDP transport error — empty list, fail-closed. */
        PDP_UNAVAILABLE,
    }

    companion object {
        /** Tag value for a JSON-RPC method this server does not implement. */
        const val UNKNOWN_METHOD = "unknown"

        /** Tag value for a tool with no capability mapping — the deny-by-default gate firing. */
        const val UNMAPPED_TOOL = "unmapped"
    }
}

/** Where the acting agent's identity came from. A bounded set — safe as a tag. */
enum class CallerIdentitySource {
    /** A validated OAuth 2.1 agent token supplied `sub` and `consent_id` (ADR-0195). */
    TOKEN,

    /** No agent token was presented, so the phase-1 placeholder identity was used (blocker #2206). */
    ANONYMOUS_FALLBACK,

    /** An agent token was present but malformed, so the call was denied fail-closed. */
    RESOLUTION_FAILED,
}
