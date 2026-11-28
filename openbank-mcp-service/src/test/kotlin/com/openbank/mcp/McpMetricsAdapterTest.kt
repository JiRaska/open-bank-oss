// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.audit.AuditResult
import com.openbank.mcp.application.McpCallAuditor
import com.openbank.mcp.application.port.out.CallerIdentitySource
import com.openbank.mcp.application.port.out.McpMetricsPort
import com.openbank.mcp.infrastructure.observability.McpCustomerClientAllowlist
import com.openbank.mcp.infrastructure.observability.McpMetricsAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class McpMetricsAdapterTest {

    private val registry = SimpleMeterRegistry()
    private val adapter = McpMetricsAdapter(registry)

    @Test
    fun `every policy decision keeps its own series`() {
        McpCallAuditor.Decision.entries.forEach {
            adapter.toolCallCompleted("list_accounts", it, AuditResult.DENIED, Duration.ofMillis(5))
        }

        McpCallAuditor.Decision.entries.forEach { decision ->
            assertThat(
                registry.get("openbank.mcp.tool_calls")
                    .tag("tool", "list_accounts").tag("decision", decision.name).tag("result", "DENIED")
                    .counter().count(),
            ).isEqualTo(1.0)
        }
        assertThat(registry.get("openbank.mcp.tool_call.duration").tag("tool", "list_accounts").timer().count())
            .isEqualTo(McpCallAuditor.Decision.entries.size.toLong())
    }

    @Test
    fun `every caller-identity source gets a distinct lower-cased tag value`() {
        CallerIdentitySource.entries.forEach { adapter.callerIdentityResolved(it) }

        CallerIdentitySource.entries.forEach { source ->
            assertThat(
                registry.get("openbank.mcp.caller_identity")
                    .tag("service", "mcp").tag("source", source.name.lowercase()).counter().count(),
            ).isEqualTo(1.0)
        }
    }

    @Test
    fun `every tools-list outcome gets a distinct lower-cased tag value`() {
        McpMetricsPort.ToolsListOutcome.entries.forEach { adapter.toolsListCompleted(it) }

        McpMetricsPort.ToolsListOutcome.entries.forEach { outcome ->
            assertThat(
                registry.get("openbank.mcp.tools_list")
                    .tag("service", "mcp").tag("outcome", outcome.name.lowercase()).counter().count(),
            ).isEqualTo(1.0)
        }
    }

    @Test
    fun `the bounded tag constants are the values the endpoint is expected to pass`() {
        adapter.requestHandled(McpMetricsPort.UNKNOWN_METHOD)
        adapter.toolCallCompleted(
            McpMetricsPort.UNMAPPED_TOOL,
            McpCallAuditor.Decision.DENY,
            AuditResult.DENIED,
            Duration.ZERO,
        )

        assertThat(registry.get("openbank.mcp.requests").tag("method", "unknown").counter().count()).isEqualTo(1.0)
        assertThat(
            registry.get("openbank.mcp.tool_calls").tag("tool", "unmapped").counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `is a silent no-op when no meter registry is resolvable`() {
        // Slim slices without a Prometheus registry must not crash a tool call.
        val noRegistry = McpMetricsAdapter(null)

        noRegistry.requestHandled("ping")
        noRegistry.toolCallCompleted("ping", McpCallAuditor.Decision.ALLOW, AuditResult.SUCCESS, Duration.ZERO)
        noRegistry.callerIdentityResolved(CallerIdentitySource.TOKEN)
        noRegistry.toolsListCompleted(McpMetricsPort.ToolsListOutcome.OK)
    }

    // ── ADR-0384 D1: channel tags ──────────────────────────────────────────────────────────

    @Test
    fun `agent and customer series share one tag-key set in a real Prometheus scrape`() {
        val prom = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val a = McpMetricsAdapter(prom, McpCustomerClientAllowlist(listOf("acme")))
        a.toolCallCompleted("list_accounts", McpCallAuditor.Decision.ALLOW, AuditResult.SUCCESS, Duration.ofMillis(1))
        a.toolCallCompleted(
            "list_accounts",
            McpCallAuditor.Decision.ALLOW,
            AuditResult.SUCCESS,
            Duration.ofMillis(1),
            McpMetricsPort.ChannelTags(McpMetricsPort.McpChannel.CUSTOMER, client = "acme", synthetic = true),
        )
        val scrape = prom.scrape().lines().filter { it.startsWith("openbank_mcp_tool_calls_total{") }

        assertThat(scrape).hasSize(2)
        assertThat(scrape).anyMatch {
            it.contains("channel=\"agent\"") &&
                it.contains("client=\"none\"") &&
                it.contains("synthetic=\"false\"")
        }
        assertThat(scrape).anyMatch {
            it.contains("channel=\"customer\"") &&
                it.contains("client=\"acme\"") &&
                it.contains("synthetic=\"true\"")
        }
    }

    @Test
    fun `the Pyrra SLO series exist in a real scrape - the 1,07s bucket and the failure counter`() {
        val prom = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val a = McpMetricsAdapter(prom, McpCustomerClientAllowlist(listOf("acme")))
        val customer = McpMetricsPort.ChannelTags(McpMetricsPort.McpChannel.CUSTOMER, client = "acme")
        a.toolCallCompleted(
            "list_accounts",
            McpCallAuditor.Decision.ALLOW,
            AuditResult.FAILURE,
            Duration.ofMillis(5),
            customer,
        )
        a.toolCallFailed("list_accounts", McpMetricsPort.ToolFailureKind.ERROR, customer)
        val scrape = prom.scrape().lines()

        // pyrra-slo-mcp-customer.yaml reads exactly these series; a rename here must fail this test.
        val customerFalse = listOf("channel=\"customer\"", "synthetic=\"false\"")
        assertThat(scrape).anyMatch { line ->
            line.startsWith("openbank_mcp_tool_call_duration_seconds_bucket{") &&
                (customerFalse + "le=\"1.073741824\"").all { line.contains(it) }
        }
        assertThat(scrape).anyMatch { line ->
            line.startsWith("openbank_mcp_tool_call_failures_total{") &&
                (customerFalse + "kind=\"error\"").all { line.contains(it) }
        }
        assertThat(scrape).anyMatch { line ->
            line.startsWith("openbank_mcp_tool_calls_total{") &&
                listOf("decision=\"ALLOW\"", "result=\"FAILURE\"").all { line.contains(it) }
        }
    }

    @Test
    fun `an operator call is tagged operator with the placeholders`() {
        adapter.toolCallCompleted(
            "list_accounts",
            McpCallAuditor.Decision.ALLOW,
            AuditResult.SUCCESS,
            Duration.ZERO,
            McpMetricsPort.ChannelTags.OPERATOR,
        )
        assertThat(
            registry.get("openbank.mcp.tool_calls").tag("channel", "operator").tag("client", "none").counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `25 allowlisted clients plus an unknown one yield exactly 26 client values`() {
        val clients = (1..25).map { "client-$it" }
        val a = McpMetricsAdapter(registry, McpCustomerClientAllowlist(clients))
        (clients + "rogue-dynamically-registered").forEach {
            a.customerRejected(McpMetricsPort.CustomerRejectReason.MISSING, it, synthetic = false)
            a.customerRejected(McpMetricsPort.CustomerRejectReason.MISSING, "$it-again", synthetic = false)
        }
        val values = registry.find("openbank.mcp.customer.rejected").counters().map { it.id.getTag("client") }.toSet()

        assertThat(values).hasSize(26).contains("unlisted")
    }

    @Test
    fun `every reject reason is tagged with its own layer`() {
        McpMetricsPort.CustomerRejectReason.entries.forEach { adapter.customerRejected(it, null, synthetic = false) }

        McpMetricsPort.CustomerRejectReason.entries.forEach { reason ->
            assertThat(
                registry.get("openbank.mcp.customer.rejected")
                    .tag("layer", reason.layer.name.lowercase()).tag("reason", reason.name.lowercase())
                    .tag("client", "unlisted").tag("synthetic", "false")
                    .counter().count(),
            ).isEqualTo(1.0)
        }
    }

    @Test
    fun `no customer meter carries a party, grant, key or subject tag`() {
        adapter.customerRejected(McpMetricsPort.CustomerRejectReason.REPLAY, "x", synthetic = true)
        adapter.toolCallCompleted(
            "list_accounts",
            McpCallAuditor.Decision.ALLOW,
            AuditResult.SUCCESS,
            Duration.ZERO,
            McpMetricsPort.ChannelTags(McpMetricsPort.McpChannel.CUSTOMER, client = "x"),
        )
        val forbidden = setOf("party", "party_ref", "jkt", "sub", "grant", "session", "ip")
        val keys = registry.meters.filter { it.id.name.startsWith("openbank.mcp") }
            .flatMap { m -> m.id.tags.map { it.key } }.toSet()

        assertThat(keys).doesNotContainAnyElementsOf(forbidden)
    }
}
