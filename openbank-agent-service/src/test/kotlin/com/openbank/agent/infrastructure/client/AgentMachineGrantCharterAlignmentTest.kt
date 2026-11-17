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
    fun `the transaction, SCT Inst, compliance and dispute tools map to capabilities some charter holds`() {
        listOf(
            "list_transactions",
            "get_transaction",
            "sepa_instant_list",
            "sepa_instant_get",
            "sepa_instant_list_by_debtor",
            // #10486 batch 8: aml / sanctions / dispute reads (query.compliance.readonly,
            // query.disputes.readonly) are granted by aml_rest_ext.rego, the sanctions bundle generator
            // and dispute_rest_ext.rego because a charter holds them.
            "aml_list_cases",
            "aml_get_case",
            "sanctions_list_checks",
            "sanctions_get_check",
            "sanctions_list_pending",
            "dispute_list",
            "dispute_get",
            "dispute_list_by_account",
            "dispute_get_timeline",
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

    /**
     * #16381: the agent identity's only sanctions WRITE is `sanctions.clear` as the four-eyes maker.
     * That is safe only while (a) a charter actually holds the capability the tool needs, (b) the
     * identity is never granted the checker's verb, and (c) the deployed four-eyes enforcement is on —
     * with it off the review would execute unapproved, so this test is the guard on that env value.
     */
    @Test
    fun `the sanctions review maker grant is charter-backed, checker-free and four-eyes enforced`() {
        assertThat(registry.capabilityOf("sanctions_review_check")).isEqualTo("sanctions.review.maker")
        assertThat(allowedByAnyCharter).contains("sanctions.review.maker")

        val gen = File("../openbank-infra/gitops/components/sanctions-service/gen-sanctions-opa-bundle.sh").readText()
        val makerRule = gen.substringAfter("allowed_reasons contains \"service-agent-sanctions-review-maker\" if {")
            .substringBefore("}")
        assertThat(makerRule).contains("machine_named(\"service-account-openbank-agent\")")
        assertThat(makerRule).contains("input.action == \"sanctions.clear\"")
        // Every rule naming the agent identity: none may reach the checker's decide verb.
        val agentRules = gen.split("allowed_reasons contains").drop(1).map { it.substringBefore("\n}") }
            .filter { "service-account-openbank-agent\")" in it }
        assertThat(agentRules).hasSize(2)
        agentRules.forEach { assertThat(it).doesNotContain("approval.decide").doesNotContain("startswith(") }

        val rollout = File("../openbank-infra/gitops/components/sanctions-service/sanctions-service.yaml").readText()
        val enforce = Regex("""name: AUTHZ_FOUR_EYES_ENFORCE\s+value: "([^"]*)"""").find(rollout)?.groupValues?.get(1)
        assertThat(enforce).describedAs("sanctions AUTHZ_FOUR_EYES_ENFORCE").isEqualTo("true")
    }
}
