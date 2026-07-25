// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.McpToolRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The transport half of the MCP read tools (ADR-0002 hexagonal): the adapter behind
 * `DownstreamReadPort`. Covers what the application layer deliberately no longer knows — which
 * downstream client a tool reaches, how arguments marshal onto it, and the bounds that keep one
 * tool call from pulling an unbounded result set.
 */
class RestDownstreamReadAdapterTest {

    private val mapper = ObjectMapper()
    private val adapter = RestDownstreamReadAdapter()

    @Test
    fun `handles every read tool the registry offers the model, and nothing else`() {
        // The catalog is application-side, the dispatch is here: a tool declared to the model but
        // absent from this adapter would fail closed as "unknown tool" at runtime. Assert the two
        // agree, so adding a tool to one without the other cannot ship.
        val registry = McpToolRegistry()
        val declared = registry.tools.map { it.name }.toSet()
        val proposalTools = setOf("draft_ticket", "flip_feature_flag")

        val expectedReads = declared - proposalTools
        assertThat(expectedReads).isNotEmpty()
        assertThat(expectedReads.filterNot { adapter.handles(it) })
            .describedAs("read tools offered to the model that this adapter cannot serve")
            .isEmpty()
        // The proposal tools are HITL, not reads — they must never resolve to a downstream call.
        assertThat(proposalTools.filter { adapter.handles(it) }).isEmpty()
        assertThat(adapter.handles("delete_everything")).isFalse()
    }

    @Test
    fun `query_loki_logs caps the line limit at 1000`() {
        adapter.lokiClient = mockk()
        every { adapter.lokiClient.queryRange(any(), any(), any(), 1000, any()) } returns
            mapper.createObjectNode().put("status", "success")

        val args = mapper.createObjectNode()
            .put("query", "{namespace=\"payments\"}")
            .put("limit", 99999)
        adapter.read("query_loki_logs", args)

        // Verifies the coerceIn(1, MAX_LOKI_LINES) cap reached the client as 1000.
        verify { adapter.lokiClient.queryRange(any(), any(), any(), 1000, any()) }
    }

    @Test
    fun `query_metrics uses an instant query without bounds and a range query with both`() {
        adapter.prometheusClient = mockk()
        every { adapter.prometheusClient.query(any(), any()) } returns
            mapper.createObjectNode().put("kind", "instant")
        every { adapter.prometheusClient.queryRange(any(), any(), any(), any()) } returns
            mapper.createObjectNode().put("kind", "range")

        val instant = adapter.read("query_metrics", mapper.createObjectNode().put("query", "up"))
        assertThat(instant["kind"].asText()).isEqualTo("instant")

        val ranged = adapter.read(
            "query_metrics",
            mapper.createObjectNode().put("query", "up").put("start", "1").put("end", "2"),
        )
        assertThat(ranged["kind"].asText()).isEqualTo("range")
        // Default resolution when the model omits `step`.
        verify { adapter.prometheusClient.queryRange("up", "1", "2", "60s") }
    }

    @Test
    fun `list_alerts asks for active, non-silenced alerts only`() {
        adapter.alertmanagerClient = mockk()
        every { adapter.alertmanagerClient.listAlerts(any(), any(), any()) } returns mapper.createArrayNode()

        adapter.read("list_alerts", mapper.createObjectNode())

        verify { adapter.alertmanagerClient.listAlerts(true, false, null) }
    }

    @Test
    fun `a missing required argument is rejected before any downstream call`() {
        adapter.accountClient = mockk()

        assertThatThrownBy { adapter.read("get_account", mapper.createObjectNode()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("accountId")

        verify(exactly = 0) { adapter.accountClient.getAccount(any()) }
    }

    @Test
    fun `a blank required argument is rejected too`() {
        adapter.accountClient = mockk()

        assertThatThrownBy { adapter.read("get_account", mapper.createObjectNode().put("accountId", "   ")) }
            .isInstanceOf(IllegalArgumentException::class.java)

        verify(exactly = 0) { adapter.accountClient.getAccount(any()) }
    }

    @Test
    fun `reading a tool this adapter does not serve throws rather than guessing a service`() {
        assertThatThrownBy { adapter.read("draft_ticket", mapper.createObjectNode()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not a downstream read tool")
    }
}
