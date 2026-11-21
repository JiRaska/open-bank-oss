// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.observability

import com.openbank.libs.audit.AuditResult
import com.openbank.libs.observability.standardPercentiles
import com.openbank.mcp.application.McpCallAuditor
import com.openbank.mcp.application.port.out.CallerIdentitySource
import com.openbank.mcp.application.port.out.McpMetricsPort
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.time.Duration

/**
 * Micrometer adapter for [McpMetricsPort] (ADR-0077 Tier C). Emits, all tagged `service="mcp"`:
 *
 *  - `openbank_mcp_requests_total{method}` — the JSON-RPC method mix, `method="unknown"` for anything
 *    this server does not implement.
 *  - `openbank_mcp_tool_calls_total{tool,decision,result}` — the AI-attribution counter, carrying the
 *    same three facts as the call's audit event. `decision="UNAVAILABLE"` is a PDP outage denying
 *    every agent; `tool="unmapped"` is the deny-by-default gate firing.
 *  - `openbank_mcp_tool_call_duration_seconds{tool}` — includes the PDP round-trip and the tool's own
 *    work, i.e. what the agent actually waits for.
 *  - `openbank_mcp_customer_rejected_total{layer,reason,client,synthetic}` — ADR-0384 D1: a
 *    customer-channel request refused before a tool ran. `client` is the allowlist short id or
 *    `unlisted`; the tool-call and duration series carry `channel`, `client` and `synthetic` too.
 *  - `openbank_mcp_caller_identity_total{source}` — `anonymous_fallback` is the count of calls still
 *    running under the phase-1 placeholder identity rather than a validated OAuth token (blocker
 *    #2206). It must be zero before a real read port replaces the stub.
 *
 * No agent id, consent id, argument value or tool result is ever a tag: the tool name is mapped to a
 * bounded value by the endpoint (see the port's cardinality note) and everything else is a closed
 * enum. The audit event remains the per-call record; this is the aggregate.
 *
 * Service-local `MeterRegistry`, null-safe via [Instance] exactly like libs `DomainMetrics`: MCP
 * tool-call counters are mcp-specific, so adding them to the shared libs facade would force a
 * fleet-wide rebuild for a one-service concern.
 */
@ApplicationScoped
class McpMetricsAdapter(
    private val registry: MeterRegistry?,
    private val allowlist: McpCustomerClientAllowlist = McpCustomerClientAllowlist(emptyList()),
) : McpMetricsPort {

    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. slim test slices). Without an explicit @Inject ctor, ArC sees two constructors,
    // registers no bean, and McpEndpoint is left with an unsatisfied dependency at build time.
    @Inject
    constructor(registryInstance: Instance<MeterRegistry>, allowlist: McpCustomerClientAllowlist) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
        allowlist,
    )

    override fun requestHandled(method: String) {
        registry?.let { r ->
            Counter.builder("openbank.mcp.requests")
                .tag("service", SERVICE)
                .tag("method", method)
                .description("MCP JSON-RPC requests by method")
                .register(r)
                .increment()
        }
    }

    override fun toolCallCompleted(
        tool: String,
        decision: McpCallAuditor.Decision,
        result: AuditResult,
        duration: Duration,
        channel: McpMetricsPort.ChannelTags,
    ) {
        val r = registry ?: return
        val tags = channelTags(channel)
        Counter.builder("openbank.mcp.tool_calls")
            .tag("service", SERVICE)
            .tag("tool", tool)
            .tag("decision", decision.name)
            .tag("result", result.name)
            .tags(tags)
            .description("MCP tool calls by tool, policy decision and outcome")
            .register(r)
            .increment()
        Timer.builder("openbank.mcp.tool_call.duration")
            .tag("service", SERVICE)
            .tag("tool", tool)
            .tags(tags)
            .standardPercentiles()
            .description("End-to-end MCP tool-call latency, including the PDP round-trip")
            .register(r)
            .record(duration)
    }

    override fun callerIdentityResolved(source: CallerIdentitySource) {
        registry?.let { r ->
            Counter.builder("openbank.mcp.caller_identity")
                .tag("service", SERVICE)
                .tag("source", source.name.lowercase())
                .description("How the acting agent's identity was established for a tool call")
                .register(r)
                .increment()
        }
    }

    override fun toolsListCompleted(outcome: McpMetricsPort.ToolsListOutcome) {
        registry?.let { r ->
            Counter.builder("openbank.mcp.tools_list")
                .tag("service", SERVICE)
                .tag("outcome", outcome.name.lowercase())
                .description("MCP tools/list discovery by outcome (ADR-0225)")
                .register(r)
                .increment()
        }
    }

    override fun requestThrottled(surface: McpMetricsPort.ThrottleSurface, caller: McpMetricsPort.ThrottleCaller) {
        registry?.let { r ->
            Counter.builder("openbank.mcp.throttled")
                .tag("service", SERVICE)
                .tag("surface", surface.name.lowercase())
                .tag("caller", caller.name.lowercase())
                .description("MCP requests refused by the rate window or in-flight cap (ADR-0383)")
                .register(r)
                .increment()
        }
    }

    override fun auditSuppressed(reason: McpMetricsPort.AuditSuppression) {
        registry?.let { r ->
            Counter.builder("openbank.mcp.audit_suppressed")
                .tag("service", SERVICE)
                .tag("reason", reason.name.lowercase())
                .description("MCP refusals whose audit row was suppressed by the per-source audit window")
                .register(r)
                .increment()
        }
    }

    override fun inputRefused(reason: McpMetricsPort.InputRefusal) {
        registry?.let { r ->
            Counter.builder("openbank.mcp.input_refused")
                .tag("service", SERVICE)
                .tag("reason", reason.name.lowercase())
                .description("MCP JSON-RPC requests refused by an input bound before dispatch (#16628)")
                .register(r)
                .increment()
        }
    }

    override fun customerRejected(reason: McpMetricsPort.CustomerRejectReason, client: String?, synthetic: Boolean) {
        registry?.let { r ->
            Counter.builder("openbank.mcp.customer.rejected")
                .tag("service", SERVICE)
                .tag("layer", reason.layer.name.lowercase())
                .tag("reason", reason.name.lowercase())
                .tag("client", allowlist.label(client))
                .tag("synthetic", synthetic.toString())
                .description("Customer-channel MCP requests refused before a tool ran, by layer and reason (ADR-0384)")
                .register(r)
                .increment()
        }
    }

    /**
     * ADR-0384 D1: `channel`, `client`, `synthetic` on every channel. Agent and operator calls carry
     * the fixed placeholders, because a meter name may have only one tag-key set. A customer call's
     * client is mapped through the allowlist, so the value set is bounded by configuration.
     */
    private fun channelTags(channel: McpMetricsPort.ChannelTags): Tags = when (channel.channel) {
        McpMetricsPort.McpChannel.CUSTOMER -> Tags.of(
            "channel",
            "customer",
            "client",
            allowlist.label(channel.client),
            "synthetic",
            channel.synthetic.toString(),
        )
        else -> Tags.of(
            "channel",
            channel.channel.name.lowercase(),
            "client",
            McpCustomerClientAllowlist.NONE,
            "synthetic",
            "false",
        )
    }

    companion object {
        private const val SERVICE = "mcp"
    }
}
