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

    /** Record one completed `tools/call`, mirroring the tags of its audit event. */
    fun toolCallCompleted(tool: String, decision: McpCallAuditor.Decision, result: AuditResult, duration: Duration)

    /** Record how the acting agent's identity was established for one `tools/call`. */
    fun callerIdentityResolved(source: CallerIdentitySource)

    /** Record one `tools/list` discovery, by outcome (ADR-0225 D4 — discovery recon is countable). */
    fun toolsListCompleted(outcome: ToolsListOutcome)

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
