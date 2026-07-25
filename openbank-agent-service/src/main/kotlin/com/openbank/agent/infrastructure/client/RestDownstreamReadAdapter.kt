// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.client

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.agent.application.port.out.DownstreamReadPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient

/** Hard cap on Loki log lines a single query_loki_logs call may pull back. */
private const val MAX_LOKI_LINES = 1000

/** Default page/limit sizes when the model omits the optional argument. */
private const val DEFAULT_LIMIT = 20
private const val DEFAULT_CATALOG_LIMIT = 50
private const val DEFAULT_LOG_LINES = 100

/**
 * The MicroProfile REST-client adapter behind [DownstreamReadPort] (ADR-0002 hexagonal).
 *
 * Everything transport-shaped lives here: the fifteen `@RegisterRestClient` interfaces, the
 * client-credentials bearer they carry, the mapping from an MCP tool name + JSON arguments onto a
 * concrete HTTP call, and the guards that keep one tool call from pulling an unbounded result set
 * (see [MAX_LOKI_LINES]). The application layer keeps the governance half — the tool catalog, the
 * charter-capability mapping, the HITL proposal tools and the AI-attributed audit — and never sees
 * JAX-RS.
 *
 * Read-only by construction: every method reached from [read] is a `@GET` on a downstream service.
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
class RestDownstreamReadAdapter : DownstreamReadPort {

    @Inject @RestClient
    lateinit var accountClient: AccountServiceClient

    @Inject @RestClient
    lateinit var transactionClient: TransactionServiceClient

    @Inject @RestClient
    lateinit var balanceClient: BalanceServiceClient

    @Inject @RestClient
    lateinit var productCatalogClient: ProductCatalogClient

    @Inject @RestClient
    lateinit var ledgerClient: LedgerServiceClient

    @Inject @RestClient
    lateinit var amlClient: AmlServiceClient

    @Inject @RestClient
    lateinit var sanctionsClient: SanctionsServiceClient

    @Inject @RestClient
    lateinit var fxClient: FxServiceClient

    @Inject @RestClient
    lateinit var clearingClient: ClearingServiceClient

    @Inject @RestClient
    lateinit var interestClient: InterestServiceClient

    @Inject @RestClient
    lateinit var disputeClient: DisputeServiceClient

    @Inject @RestClient
    lateinit var sepaInstantClient: SepaInstantServiceClient

    @Inject @RestClient
    lateinit var prometheusClient: PrometheusClient

    @Inject @RestClient
    lateinit var lokiClient: LokiClient

    @Inject @RestClient
    lateinit var alertmanagerClient: AlertmanagerClient

    override fun handles(toolName: String): Boolean = toolName in READ_TOOLS

    // A flat dispatch over the read-tool vocabulary: one branch per registered tool, each a single
    // downstream GET. Splitting it by service would only scatter the very table it exists to be.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun read(toolName: String, arguments: JsonNode): JsonNode = when (toolName) {
        "get_account" -> accountClient.getAccount(arguments.requiredString("accountId"))
        "get_account_by_iban" -> accountClient.getAccountByIban(arguments.requiredString("iban"))
        "get_account_balance" -> accountClient.getBalance(arguments.requiredString("accountId"))
        "list_transactions" -> transactionClient.listTransactions(
            accountId = arguments.requiredString("accountId"),
            limit = arguments["limit"]?.asInt() ?: DEFAULT_LIMIT,
            cursor = arguments["cursor"]?.asText(),
        )
        "get_transaction" -> transactionClient.getTransaction(arguments.requiredString("transactionId"))
        "get_balance_holds" -> balanceClient.getHolds(arguments.requiredString("accountId"))
        "list_products" -> productCatalogClient.listProducts(arguments["limit"]?.asInt() ?: DEFAULT_CATALOG_LIMIT)
        "get_product" -> productCatalogClient.getProduct(arguments.requiredString("productId"))
        "get_product_fees" -> productCatalogClient.getProductFees(arguments.requiredString("productId"))
        "list_ledger_journals" -> ledgerClient.listJournals(arguments["limit"]?.asInt() ?: DEFAULT_LIMIT)
        "get_trial_balance" -> ledgerClient.trialBalance(arguments["asOf"]?.asText())
        "aml_list_cases" -> amlClient.listCases(
            status = arguments["status"]?.asText(),
            partyId = arguments["partyId"]?.asText(),
            limit = arguments["limit"]?.asInt() ?: DEFAULT_LIMIT,
            offset = 0,
        )
        "aml_get_case" -> amlClient.getCase(arguments.requiredString("caseId"))
        "sanctions_list_checks" -> sanctionsClient.listChecks()
        "sanctions_get_check" -> sanctionsClient.getCheck(arguments.requiredString("id"))
        "sanctions_list_pending" -> sanctionsClient.listPending()
        "fx_list_rates" -> fxClient.getRates()
        "fx_get_rate" -> fxClient.getRate(
            base = arguments.requiredString("base"),
            quote = arguments.requiredString("quote"),
            source = arguments["source"]?.asText(),
        )
        "clearing_list_batches" -> clearingClient.listBatches(
            status = arguments["status"]?.asText(),
            page = 0,
            size = arguments["size"]?.asInt() ?: DEFAULT_LIMIT,
        )
        "clearing_get_batch" -> clearingClient.getBatch(arguments.requiredString("batchId"))
        "clearing_get_batch_items" -> clearingClient.getBatchItems(arguments.requiredString("batchId"))
        "interest_list_accruals" -> interestClient.listAccruals()
        "interest_get_accruals" -> interestClient.getAccruals(
            accountId = arguments.requiredString("accountId"),
            from = arguments["from"]?.asText(),
            to = arguments["to"]?.asText(),
        )
        "interest_accrual_summary" -> interestClient.getSummary(
            accountId = arguments.requiredString("accountId"),
            from = arguments["from"]?.asText() ?: "",
            to = arguments["to"]?.asText() ?: "",
        )
        "dispute_list" -> disputeClient.list(arguments["status"]?.asText())
        "dispute_get" -> disputeClient.get(arguments.requiredString("disputeId"))
        "dispute_list_by_account" -> disputeClient.listByAccount(arguments.requiredString("accountId"))
        "dispute_get_timeline" -> disputeClient.getTimeline(arguments.requiredString("disputeId"))
        "sepa_instant_list" -> sepaInstantClient.listPayments()
        "sepa_instant_get" -> sepaInstantClient.getPayment(arguments.requiredString("paymentId"))
        "sepa_instant_list_by_debtor" -> sepaInstantClient.listByDebtor(
            debtorAccountId = arguments.requiredString("debtorAccountId"),
            page = 0,
            size = DEFAULT_LIMIT,
        )
        "query_metrics" -> queryMetrics(arguments)
        "query_loki_logs" -> lokiClient.queryRange(
            query = arguments.requiredString("query"),
            start = arguments["start"]?.asText(),
            end = arguments["end"]?.asText(),
            // Cap the line count so a broad selector can't pull an unbounded result set.
            limit = (arguments["limit"]?.asInt() ?: DEFAULT_LOG_LINES).coerceIn(1, MAX_LOKI_LINES),
            direction = "backward",
        )
        "list_alerts" -> alertmanagerClient.listAlerts(
            active = true,
            silenced = false,
            filter = arguments["filter"]?.asText(),
        )
        else -> throw IllegalArgumentException("'$toolName' is not a downstream read tool")
    }

    /** Range query when both bounds are given; otherwise an instant query. */
    private fun queryMetrics(arguments: JsonNode): JsonNode {
        val query = arguments.requiredString("query")
        val start = arguments["start"]?.asText()
        val end = arguments["end"]?.asText()
        return if (!start.isNullOrBlank() && !end.isNullOrBlank()) {
            prometheusClient.queryRange(query, start, end, arguments["step"]?.asText() ?: DEFAULT_STEP)
        } else {
            prometheusClient.query(query, arguments["time"]?.asText())
        }
    }

    private fun JsonNode.requiredString(field: String): String = this[field]?.asText()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Required field '$field' is missing or blank")

    private companion object {
        const val DEFAULT_STEP = "60s"

        /**
         * The read tools this adapter serves. Kept in lock-step with the [read] dispatch by
         * `RestDownstreamReadAdapterTest`, and with the application-side tool catalog by
         * `McpToolRegistryTest` — a tool declared to the model but absent here would fail closed
         * as an unknown tool, never silently reach a wrong service.
         */
        val READ_TOOLS = setOf(
            "get_account",
            "get_account_by_iban",
            "get_account_balance",
            "list_transactions",
            "get_transaction",
            "get_balance_holds",
            "list_products",
            "get_product",
            "get_product_fees",
            "list_ledger_journals",
            "get_trial_balance",
            "aml_list_cases",
            "aml_get_case",
            "sanctions_list_checks",
            "sanctions_get_check",
            "sanctions_list_pending",
            "fx_list_rates",
            "fx_get_rate",
            "clearing_list_batches",
            "clearing_get_batch",
            "clearing_get_batch_items",
            "interest_list_accruals",
            "interest_get_accruals",
            "interest_accrual_summary",
            "dispute_list",
            "dispute_get",
            "dispute_list_by_account",
            "dispute_get_timeline",
            "sepa_instant_list",
            "sepa_instant_get",
            "sepa_instant_list_by_debtor",
            "query_metrics",
            "query_loki_logs",
            "list_alerts",
        )
    }
}
