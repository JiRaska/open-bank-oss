// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.mcp.infrastructure.read

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * #10486 batch 7: service-account-openbank-mcp's upstream grants are derived from the charters. A read
 * port is only reached after the MCP gate allowed the tool's capability (McpToolRegistry.capabilities),
 * so the machine identity needs an upstream read exactly when SOME charter holds that capability:
 *
 * - `list_transactions` → `query.transaction.readonly`, held by mcp-anonymous → transaction.list granted;
 * - `get_payment_confirmation` → `query.payment_confirmation.readonly`, held by NO charter → sepa-payment
 *   and domestic-payment grant nothing, and the tool stays unreachable end to end.
 *
 * If a charter gains the payment-confirmation capability, the second test fails: add
 * `sepaPayment.read` / `domestic-payment.read` rules for service-account-openbank-mcp (and ROLE_API on
 * those two endpoints' RBAC) in the same change, or the tool 403s.
 */
class McpMachineGrantCharterAlignmentTest {

    private val allowedByAnyCharter: Set<String> = run {
        val doc = Yaml().load<Map<String, Any>>(File("../openbank-libs/governance/agents.yaml").readText())
        (doc["agents"] as List<*>).flatMap { agent ->
            val tools = (agent as Map<*, *>)["tools"] as? Map<*, *>
            (tools?.get("allow") as? List<*>).orEmpty().map { it.toString() }
        }.toSet()
    }

    private val registrySource =
        File("src/main/kotlin/com/openbank/mcp/application/McpToolRegistry.kt").readText()

    @Test
    fun `the transaction list maps to a capability a charter holds`() {
        assertThat(registrySource).contains("\"list_transactions\" to \"query.transaction.readonly\"")
        assertThat(allowedByAnyCharter).contains("query.transaction.readonly")
    }

    @Test
    fun `no charter holds the payment-confirmation capability, so the mcp identity has no payment grant`() {
        assertThat(registrySource)
            .contains("\"get_payment_confirmation\" to \"query.payment_confirmation.readonly\"")
        assertThat(allowedByAnyCharter).doesNotContain("query.payment_confirmation.readonly")
        val payments = "../openbank-infra/gitops/components/payments"
        listOf("$payments/sepa_payment_rest_ext.rego", "$payments/gen-domestic-payment-opa-bundle.sh").forEach {
            assertThat(File(it).readText()).describedAs(it).doesNotContain("service-account-openbank-mcp\"")
        }
    }
}
