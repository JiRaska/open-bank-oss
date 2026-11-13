// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.agent.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Maps each MCP tool to the downstream **service** it reaches and that service's product **domain**
 * from the governance declarations and canonical taxonomy packaged at build time. Surfaced on the tools/list response so the admin-ui coverage grid groups verb-first tools
 * (get_account, list_transactions, …) by service instead of a fragile name-prefix heuristic.
 *
 * Lives outside [McpToolRegistry] purely so that class stays under the detekt LargeClass budget; the
 * two are kept in lock-step by the McpToolRegistryTest invariant (every registered tool resolves to a
 * service + domain), so a newly registered tool can't go uncharted.
 */
object ToolServiceCatalog {
    private val services: Map<String, String> = mapOf(
        "get_account" to "account-service",
        "get_account_by_iban" to "account-service",
        "get_account_balance" to "balance-service",
        "get_balance_holds" to "balance-service",
        "list_transactions" to "transaction-service",
        "get_transaction" to "transaction-service",
        "list_products" to "product-catalog",
        "get_product" to "product-catalog",
        "get_product_fees" to "product-catalog",
        "get_catalog_revision" to "product-catalog",
        "list_ledger_journals" to "ledger-service",
        "get_trial_balance" to "ledger-service",
        "aml_list_cases" to "aml-service",
        "aml_get_case" to "aml-service",
        "sanctions_list_checks" to "sanctions-service",
        "sanctions_get_check" to "sanctions-service",
        "sanctions_list_pending" to "sanctions-service",
        "fx_list_rates" to "fx-service",
        "fx_get_rate" to "fx-service",
        "clearing_list_batches" to "clearing-service",
        "clearing_get_batch" to "clearing-service",
        "clearing_get_batch_items" to "clearing-service",
        "sepa_instant_list" to "sepa-instant-service",
        "sepa_instant_get" to "sepa-instant-service",
        "sepa_instant_list_by_debtor" to "sepa-instant-service",
        "interest_list_accruals" to "interest-service",
        "interest_get_accruals" to "interest-service",
        "interest_accrual_summary" to "interest-service",
        "dispute_list" to "dispute-service",
        "dispute_get" to "dispute-service",
        "dispute_list_by_account" to "dispute-service",
        "dispute_get_timeline" to "dispute-service",
        "query_metrics" to "prometheus",
        "query_loki_logs" to "loki",
        "list_alerts" to "alertmanager",
        // Agent-local HITL tools — no downstream banking service; grouped under the agent itself.
        "draft_ticket" to "agent-service",
        "flip_feature_flag" to "agent-service",
    ) + ProductAuthoringTools.CAPABILITIES.keys.associateWith { "agent-service" }

    private val labels: Map<String, String> = requireNotNull(
        javaClass.classLoader.getResourceAsStream("business-domains/taxonomy.json"),
    ) { "Canonical business-domain taxonomy is missing from the MCP image" }.use { stream ->
        ObjectMapper().readTree(stream).get("domains").associate { domain ->
            domain.get("id").asText() to domain.get("labelEn").asText()
        }
    }

    private val domains = services.values.toSet().associateWith { service ->
        // The MCP service field predates the module name; keep its advertised identity compatible.
        val module = if (service == "sepa-instant-service") "openbank-sepa-instant" else "openbank-$service"
        javaClass.classLoader.getResourceAsStream("business-domains/$module/governance.yaml")
            ?.use { stream ->
                val declaration = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any?>>(stream)
                (declaration["dataDomain"] as? String)?.let(labels::get) ?: "Unclassified"
            } ?: "Unclassified"
    }

    /** The downstream service a tool reaches, or null when unmapped. */
    fun serviceOf(toolName: String): String? = services[toolName]

    /** Canonical ownership label; external infrastructure is unclassified, unknown tools return null. */
    fun domainOf(toolName: String): String? = services[toolName]?.let { domains[it] }
}
