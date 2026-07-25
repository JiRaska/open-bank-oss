// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.audit.AuditResult
import com.openbank.mcp.application.McpCallAuditor
import com.openbank.mcp.application.port.out.CallerIdentitySource
import com.openbank.mcp.application.port.out.McpMetricsPort
import com.openbank.mcp.infrastructure.observability.McpMetricsAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
    }
}
