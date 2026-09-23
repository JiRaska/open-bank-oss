// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.client

import com.openbank.agent.application.McpToolRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * #10486 batch 7: service-account-openbank-agent's upstream grants are derived from the charters, not
 * chosen freely. A tool's REST call only happens after the charter gate allowed the tool's capability,
 * so the agent identity needs an upstream read exactly when SOME charter allows that capability:
 *
 * - `query.ledger.readonly` (list/get transaction) and `query.payments.readonly` (SCT Inst) are held
 *   by a charter, so transaction_rest_ext.rego and sepa_instant_rest_ext.rego grant the reads;
 * - `query.interest.readonly` is held by NO charter, so interest-service grants nothing and the
 *   interest tools stay unreachable end to end.
 *
 * If a charter gains `query.interest.readonly`, the second test fails: add an interest read rule for
 * service-account-openbank-agent in interest_rest_ext.rego in the same change, or the tool 403s.
 */
class AgentMachineGrantCharterAlignmentTest {

    private val allowedByAnyCharter: Set<String> = run {
        val doc = Yaml().load<Map<String, Any>>(File("../openbank-libs/governance/agents.yaml").readText())
        (doc["agents"] as List<*>).flatMap { agent ->
            val tools = (agent as Map<*, *>)["tools"] as? Map<*, *>
            (tools?.get("allow") as? List<*>).orEmpty().map { it.toString() }
        }.toSet()
    }

    private val registry = McpToolRegistry()

    @Test
    fun `the transaction and SCT Inst tools map to capabilities some charter holds`() {
        listOf(
            "list_transactions",
            "get_transaction",
            "sepa_instant_list",
            "sepa_instant_get",
            "sepa_instant_list_by_debtor",
        ).forEach { tool ->
            assertThat(registry.capabilityOf(tool)).describedAs(tool).isIn(allowedByAnyCharter)
        }
    }

    @Test
    fun `no charter holds the interest capability, so the agent identity has no interest grant`() {
        listOf("interest_list_accruals", "interest_get_accruals", "interest_accrual_summary").forEach { tool ->
            assertThat(registry.capabilityOf(tool)).describedAs(tool).isEqualTo("query.interest.readonly")
        }
        assertThat(allowedByAnyCharter).doesNotContain("query.interest.readonly")
        val interestRego = File(
            "../openbank-infra/gitops/components/interest-service/interest_rest_ext.rego",
        ).readText()
        assertThat(interestRego).doesNotContain("service-account-openbank-agent")
    }
}
