// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The tool -> service -> domain mapping the admin-ui coverage grid groups by (#744). The invariant
 * that matters is completeness against the live registry: a tool the model is offered but that
 * resolves to no service renders as an uncharted cell.
 */
class ToolServiceCatalogTest {

    @Test
    fun `every registered MCP tool resolves to both a service and a domain`() {
        val unmapped = McpToolRegistry().tools.map { it.name }.filter {
            ToolServiceCatalog.serviceOf(it) == null || ToolServiceCatalog.domainOf(it) == null
        }

        assertThat(unmapped).describedAs("registered tools with no service/domain mapping").isEmpty()
    }

    @Test
    fun `an unmapped tool name yields null on both lookups rather than a placeholder`() {
        assertThat(ToolServiceCatalog.serviceOf("delete_everything")).isNull()
        assertThat(ToolServiceCatalog.domainOf("delete_everything")).isNull()
    }

    @Test
    fun `the domain is derived through the service, so tools of one service share a domain`() {
        assertThat(ToolServiceCatalog.serviceOf("get_account")).isEqualTo("account-service")
        assertThat(ToolServiceCatalog.domainOf("get_account")).isEqualTo("Core Banking")
        assertThat(ToolServiceCatalog.domainOf("get_account_by_iban"))
            .isEqualTo(ToolServiceCatalog.domainOf("get_account"))
    }

    @Test
    fun `the agent-local HITL tools reach no downstream banking service`() {
        listOf("draft_ticket", "flip_feature_flag").forEach {
            assertThat(ToolServiceCatalog.serviceOf(it)).isEqualTo("agent-service")
            assertThat(ToolServiceCatalog.domainOf(it)).isEqualTo("Governance")
        }
    }

    @Test
    fun `observability tools are grouped away from the banking domains`() {
        assertThat(ToolServiceCatalog.domainOf("query_metrics")).isEqualTo("Observability")
        assertThat(ToolServiceCatalog.domainOf("query_loki_logs")).isEqualTo("Observability")
        assertThat(ToolServiceCatalog.domainOf("list_alerts")).isEqualTo("Observability")
        assertThat(ToolServiceCatalog.domainOf("aml_list_cases")).isEqualTo("Compliance")
    }
}
