// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.domain.ToolCallResult
import com.openbank.agent.domain.ToolContent
import com.openbank.agent.domain.ToolDefinition
import com.openbank.agent.infrastructure.client.AccountServiceClient
import com.openbank.agent.infrastructure.client.AlertmanagerClient
import com.openbank.agent.infrastructure.client.AmlServiceClient
import com.openbank.agent.infrastructure.client.BalanceServiceClient
import com.openbank.agent.infrastructure.client.ClearingServiceClient
import com.openbank.agent.infrastructure.client.DisputeServiceClient
import com.openbank.agent.infrastructure.client.FxServiceClient
import com.openbank.agent.infrastructure.client.InterestServiceClient
import com.openbank.agent.infrastructure.client.LedgerServiceClient
import com.openbank.agent.infrastructure.client.LokiClient
import com.openbank.agent.infrastructure.client.ProductCatalogClient
import com.openbank.agent.infrastructure.client.PrometheusClient
import com.openbank.agent.infrastructure.client.SanctionsServiceClient
import com.openbank.agent.infrastructure.client.SepaInstantServiceClient
import com.openbank.agent.infrastructure.client.TransactionServiceClient
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/** Hard cap on Loki log lines a single query_loki_logs call may pull back. */
private const val MAX_LOKI_LINES = 1000

@ApplicationScoped
class McpToolRegistry {

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

    @Inject lateinit var proposalService: ProposalService

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var auditPublisher: AuditEventPublisher

    private val log = Logger.getLogger(McpToolRegistry::class.java)

    val tools: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "get_account",
            description = "Get a bank account by UUID (IBAN, type, status, currency, owner).",
            inputSchema = schema(
                "accountId" to (stringProp("UUID of the account") to true),
            ),
        ),
        ToolDefinition(
            name = "get_account_by_iban",
            description = "Get a bank account by IBAN.",
            inputSchema = schema(
                "iban" to (stringProp("IBAN in standard format, e.g. CZ6508000000192000145399") to true),
            ),
        ),
        ToolDefinition(
            name = "get_account_balance",
            description = "Get an account balance (available/current/reserved/pending).",
            inputSchema = schema(
                "accountId" to (stringProp("UUID of the account") to true),
            ),
        ),
        ToolDefinition(
            name = "list_transactions",
            description = "List an account's transactions (paginated).",
            inputSchema = schema(
                "accountId" to (stringProp("UUID of the account") to true),
                "limit" to (intProp("Number of transactions to return, default 20, max 100") to false),
                "cursor" to (stringProp("Pagination cursor from previous response") to false),
            ),
        ),
        ToolDefinition(
            name = "get_transaction",
            description = "Get a transaction by UUID.",
            inputSchema = schema(
                "transactionId" to (stringProp("UUID of the transaction") to true),
            ),
        ),
        ToolDefinition(
            name = "get_balance_holds",
            description = "List active balance holds on an account.",
            inputSchema = schema(
                "accountId" to (stringProp("UUID of the account") to true),
            ),
        ),
        ToolDefinition(
            name = "list_products",
            description = "List products from the catalogue (codes, type, terms).",
            inputSchema = schema(
                "limit" to (intProp("Max products to return, default 50") to false),
            ),
        ),
        ToolDefinition(
            name = "get_product",
            description = "Get a catalogue product by id.",
            inputSchema = schema(
                "productId" to (stringProp("Product id or code") to true),
            ),
        ),
        ToolDefinition(
            name = "get_product_fees",
            description = "Get a product's fee schedule.",
            inputSchema = schema(
                "productId" to (stringProp("Product id or code") to true),
            ),
        ),
        ToolDefinition(
            name = "list_ledger_journals",
            description = "List recent general-ledger journals.",
            inputSchema = schema(
                "limit" to (intProp("Max journals to return, default 20") to false),
            ),
        ),
        ToolDefinition(
            name = "get_trial_balance",
            description = "Get the GL trial balance as of a date.",
            inputSchema = schema(
                "asOf" to (stringProp("As-of date YYYY-MM-DD; omit for latest") to false),
            ),
        ),
        // ── AML (compliance) ──────────────────────────────────────────────────
        ToolDefinition(
            name = "aml_list_cases",
            description = "List AML cases, optionally filtered by status " +
                "(OPEN/UNDER_REVIEW/CLOSED/ESCALATED/SAR_FILED) or party.",
            inputSchema = schema(
                "status" to (stringProp("Case status filter, optional") to false),
                "partyId" to (stringProp("Party UUID filter, optional") to false),
                "limit" to (intProp("Max cases to return, default 20") to false),
            ),
        ),
        ToolDefinition(
            name = "aml_get_case",
            description = "Get an AML case by id (status, screening type, decision).",
            inputSchema = schema("caseId" to (stringProp("UUID of the AML case") to true)),
        ),
        // ── Sanctions (compliance) ────────────────────────────────────────────
        ToolDefinition(
            name = "sanctions_list_checks",
            description = "List recent sanctions screening checks.",
            inputSchema = schema(),
        ),
        ToolDefinition(
            name = "sanctions_get_check",
            description = "Get a sanctions screening check by id (entity, hits, review state).",
            inputSchema = schema("id" to (stringProp("UUID of the sanctions check") to true)),
        ),
        ToolDefinition(
            name = "sanctions_list_pending",
            description = "List sanctions screenings pending review.",
            inputSchema = schema(),
        ),
        // ── FX (payments/trading) ─────────────────────────────────────────────
        ToolDefinition(
            name = "fx_list_rates",
            description = "List all current FX rates (base, quote, rate, timestamp).",
            inputSchema = schema(),
        ),
        ToolDefinition(
            name = "fx_get_rate",
            description = "Get the FX rate between two currencies, optionally from a source (e.g. CNB).",
            inputSchema = schema(
                "base" to (stringProp("Base currency code, e.g. EUR") to true),
                "quote" to (stringProp("Quote currency code, e.g. CZK") to true),
                "source" to (stringProp("Rate source, e.g. CNB; optional") to false),
            ),
        ),
        // ── Clearing (payments operations) ────────────────────────────────────
        ToolDefinition(
            name = "clearing_list_batches",
            description = "List clearing batches, optionally filtered by status (PENDING/PROCESSING/SETTLED/FAILED).",
            inputSchema = schema(
                "status" to (stringProp("Batch status filter, optional") to false),
                "size" to (intProp("Page size, default 20") to false),
            ),
        ),
        ToolDefinition(
            name = "clearing_get_batch",
            description = "Get a clearing batch by id (status, item count, total amount).",
            inputSchema = schema("batchId" to (stringProp("UUID of the clearing batch") to true)),
        ),
        ToolDefinition(
            name = "clearing_get_batch_items",
            description = "List the items inside a clearing batch.",
            inputSchema = schema("batchId" to (stringProp("UUID of the clearing batch") to true)),
        ),
        // ── Interest (core banking) ───────────────────────────────────────────
        ToolDefinition(
            name = "interest_list_accruals",
            description = "List recent interest accruals across accounts.",
            inputSchema = schema(),
        ),
        ToolDefinition(
            name = "interest_get_accruals",
            description = "Get interest accruals for an account, optionally within a date range.",
            inputSchema = schema(
                "accountId" to (stringProp("UUID of the account") to true),
                "from" to (stringProp("From date YYYY-MM-DD, optional") to false),
                "to" to (stringProp("To date YYYY-MM-DD, optional") to false),
            ),
        ),
        ToolDefinition(
            name = "interest_accrual_summary",
            description = "Get the accrual summary for an account (total accrued, average rate).",
            inputSchema = schema(
                "accountId" to (stringProp("UUID of the account") to true),
                "from" to (stringProp("From date YYYY-MM-DD, optional") to false),
                "to" to (stringProp("To date YYYY-MM-DD, optional") to false),
            ),
        ),
        // ── Disputes (customer support) ───────────────────────────────────────
        ToolDefinition(
            name = "dispute_list",
            description = "List disputes, optionally filtered by status (OPEN/RESOLVED/ESCALATED/WITHDRAWN).",
            inputSchema = schema("status" to (stringProp("Dispute status filter, optional") to false)),
        ),
        ToolDefinition(
            name = "dispute_get",
            description = "Get a dispute by id (status, reference, amount, reason).",
            inputSchema = schema("disputeId" to (stringProp("UUID of the dispute") to true)),
        ),
        ToolDefinition(
            name = "dispute_list_by_account",
            description = "List disputes raised against an account.",
            inputSchema = schema("accountId" to (stringProp("UUID of the account") to true)),
        ),
        ToolDefinition(
            name = "dispute_get_timeline",
            description = "Get the event timeline of a dispute.",
            inputSchema = schema("disputeId" to (stringProp("UUID of the dispute") to true)),
        ),
        // ── SEPA Instant (payments) ───────────────────────────────────────────
        ToolDefinition(
            name = "sepa_instant_list",
            description = "List recent SEPA Instant (SCT Inst) payments.",
            inputSchema = schema(),
        ),
        ToolDefinition(
            name = "sepa_instant_get",
            description = "Get a SEPA Instant payment by id (status, amount, timestamps).",
            inputSchema = schema("paymentId" to (stringProp("UUID of the SEPA Instant payment") to true)),
        ),
        ToolDefinition(
            name = "sepa_instant_list_by_debtor",
            description = "List SEPA Instant payments for a debtor account.",
            inputSchema = schema("debtorAccountId" to (stringProp("UUID of the debtor account") to true)),
        ),
        // ── Observability (Prometheus / Loki / Alertmanager) — read-only telemetry of the
        // running bank for the oversight plane. Reads metrics/logs/alerts; never silences or writes.
        ToolDefinition(
            name = "query_metrics",
            description = "Query Prometheus metrics with PromQL. Omit start/end for an instant query; " +
                "provide both (RFC3339 or unix seconds) for a range query.",
            inputSchema = schema(
                "query" to
                    (stringProp("PromQL expression, e.g. sum(rate(http_server_requests_seconds_count[5m]))") to true),
                "start" to (stringProp("Range start (RFC3339 or unix seconds); omit for instant query") to false),
                "end" to (stringProp("Range end (RFC3339 or unix seconds); omit for instant query") to false),
                "step" to (stringProp("Range resolution step, e.g. 30s, 1m (default 60s)") to false),
            ),
        ),
        ToolDefinition(
            name = "query_loki_logs",
            description = "Query application/pod logs from Loki with LogQL over a time range.",
            inputSchema = schema(
                "query" to (stringProp("LogQL selector, e.g. {namespace=\"payments\"} |= \"ERROR\"") to true),
                "start" to (stringProp("Range start (unix nanoseconds or RFC3339); default 1h ago") to false),
                "end" to (stringProp("Range end (unix nanoseconds or RFC3339); default now") to false),
                "limit" to (intProp("Max log lines to return, default 100, max 1000") to false),
            ),
        ),
        ToolDefinition(
            name = "list_alerts",
            description = "List currently firing alerts from Alertmanager (active, non-silenced by default).",
            inputSchema = schema(
                "filter" to (stringProp("Optional Alertmanager matcher, e.g. severity=\"critical\"") to false),
            ),
        ),
        // ── Write-proposal (HITL) — the ONLY non-read tool. Records a reviewable proposal
        // into the approval queue (ADR-0031 D4); a human approves/rejects before anything
        // happens. write_proposal tier; gated on the draft.ticket capability.
        ToolDefinition(
            name = "draft_ticket",
            description = "Record a proposal for a human to review and approve (it has NO effect " +
                "until approved). Use when the operator should take an action — never to perform one.",
            inputSchema = schema(
                "title" to (stringProp("Short title of the proposed action") to true),
                "rationale" to (stringProp("Why this is recommended (evidence/grounding)") to true),
                "suggested_action" to (stringProp("The concrete action a human would take") to true),
            ),
        ),
        ToolDefinition(
            name = "flip_feature_flag",
            description = "Propose a feature-flag flip for human approval (four-eyes, ADR-0067/issue #419). " +
                "The flip has NO runtime effect until an operator approves it in the admin-ui approval queue. " +
                "MONEY_PATH flags (instant-payments-enabled, fx-revaluation-enabled, lending-disbursement-enabled) " +
                "always require a second approver. Prohibited safety controls can never be flipped.",
            inputSchema = schema(
                "flagKey" to (stringProp("The flag key to flip (kebab-case, e.g. instant-payments-enabled)") to true),
                "targetVariant" to
                    (
                        stringProp(
                            "Target variant name — typically 'on' or 'off', or the named variant in the flag definition",
                        ) to
                            true
                        ),
                "rationale" to
                    (stringProp("Business justification for the flip (required for the audit trail)") to true),
            ),
        ),
    )

    /**
     * Maps each MCP tool to the charter capability the OPA policy gates on (ADR-0031 D2).
     * The MCP wire name (`get_account`) is an implementation detail; charters in agents.yaml
     * grant *capabilities* (`query.ledger.readonly`), so the gate must translate before it asks
     * the PDP. A tool absent here has no capability and is denied by default — registering a new
     * tool without a capability mapping must fail closed, not silently bypass governance.
     */
    private val capabilities: Map<String, String> = mapOf(
        "get_account" to "query.ledger.readonly",
        "get_account_by_iban" to "query.ledger.readonly",
        "get_account_balance" to "query.ledger.readonly",
        "list_transactions" to "query.ledger.readonly",
        "get_transaction" to "query.ledger.readonly",
        "get_balance_holds" to "query.ledger.readonly",
        // Product-catalogue reads are a distinct capability (query.catalog.readonly) — the same one the
        // aml-oversight charter uses; the ui-assistant charter grants it explicitly (agents.yaml).
        "list_products" to "query.catalog.readonly",
        "get_product" to "query.catalog.readonly",
        "get_product_fees" to "query.catalog.readonly",
        // Journal posting/listing touches the GL write model — query.ledger.readonly is correct.
        "list_ledger_journals" to "query.ledger.readonly",
        // Trial balance is a GL aggregate read (Refs #299 / ADR-0031): dedicated query.gl.readonly
        // capability so GL-sensitive reads can be granted independently of journal posting access.
        "get_trial_balance" to "query.gl.readonly",
        // Compliance reads (AML cases + sanctions screenings) — a distinct read capability the
        // compliance-officer charter owns and the ui-assistant is granted (agents.yaml).
        "aml_list_cases" to "query.compliance.readonly",
        "aml_get_case" to "query.compliance.readonly",
        "sanctions_list_checks" to "query.compliance.readonly",
        "sanctions_get_check" to "query.compliance.readonly",
        "sanctions_list_pending" to "query.compliance.readonly",
        // Payments-operations reads (FX rates, clearing batches, SEPA Instant payments).
        "fx_list_rates" to "query.payments.readonly",
        "fx_get_rate" to "query.payments.readonly",
        "clearing_list_batches" to "query.payments.readonly",
        "clearing_get_batch" to "query.payments.readonly",
        "clearing_get_batch_items" to "query.payments.readonly",
        "sepa_instant_list" to "query.payments.readonly",
        "sepa_instant_get" to "query.payments.readonly",
        "sepa_instant_list_by_debtor" to "query.payments.readonly",
        // Interest accruals (core banking, but its own service — a focused read capability).
        "interest_list_accruals" to "query.interest.readonly",
        "interest_get_accruals" to "query.interest.readonly",
        "interest_accrual_summary" to "query.interest.readonly",
        // Disputes / chargebacks (customer support).
        "dispute_list" to "query.disputes.readonly",
        "dispute_get" to "query.disputes.readonly",
        "dispute_list_by_account" to "query.disputes.readonly",
        "dispute_get_timeline" to "query.disputes.readonly",
        // Observability reads (Prometheus / Loki / Alertmanager) — telemetry of the running bank.
        "query_metrics" to "query.observability.readonly",
        "query_loki_logs" to "query.observability.readonly",
        "list_alerts" to "query.observability.readonly",
        // The single write_proposal tool — produces a reviewable artifact, never a direct effect.
        "draft_ticket" to "draft.ticket",
        // Feature-flag flip proposal — four-eyes gated for MONEY_PATH flags (ADR-0067 / issue #419).
        "flip_feature_flag" to "flags.write",
    )

    /** The charter capability a tool requires, or null when the tool is unmapped (deny-by-default). */
    fun capabilityOf(toolName: String): String? = capabilities[toolName]

    /** The downstream service a tool reaches (#744), or null when unmapped. See [ToolServiceCatalog]. */
    fun serviceOf(toolName: String): String? = ToolServiceCatalog.serviceOf(toolName)

    /** The product domain of a tool's service (#744), or null when unmapped. */
    fun domainOf(toolName: String): String? = ToolServiceCatalog.domainOf(toolName)

    fun call(toolName: String, arguments: JsonNode?, actorId: String = "unknown"): ToolCallResult {
        return try {
            val args = arguments ?: objectMapper.createObjectNode()
            val result = when (toolName) {
                "get_account" -> accountClient.getAccount(args.requiredString("accountId"))
                "get_account_by_iban" -> accountClient.getAccountByIban(args.requiredString("iban"))
                "get_account_balance" -> accountClient.getBalance(args.requiredString("accountId"))
                "list_transactions" -> transactionClient.listTransactions(
                    accountId = args.requiredString("accountId"),
                    limit = args["limit"]?.asInt() ?: 20,
                    cursor = args["cursor"]?.asText(),
                )
                "get_transaction" -> transactionClient.getTransaction(args.requiredString("transactionId"))
                "get_balance_holds" -> balanceClient.getHolds(args.requiredString("accountId"))
                "list_products" -> productCatalogClient.listProducts(args["limit"]?.asInt() ?: 50)
                "get_product" -> productCatalogClient.getProduct(args.requiredString("productId"))
                "get_product_fees" -> productCatalogClient.getProductFees(args.requiredString("productId"))
                "list_ledger_journals" -> ledgerClient.listJournals(args["limit"]?.asInt() ?: 20)
                "get_trial_balance" -> ledgerClient.trialBalance(args["asOf"]?.asText())
                "aml_list_cases" -> amlClient.listCases(
                    status = args["status"]?.asText(),
                    partyId = args["partyId"]?.asText(),
                    limit = args["limit"]?.asInt() ?: 20,
                    offset = 0,
                )
                "aml_get_case" -> amlClient.getCase(args.requiredString("caseId"))
                "sanctions_list_checks" -> sanctionsClient.listChecks()
                "sanctions_get_check" -> sanctionsClient.getCheck(args.requiredString("id"))
                "sanctions_list_pending" -> sanctionsClient.listPending()
                "fx_list_rates" -> fxClient.getRates()
                "fx_get_rate" -> fxClient.getRate(
                    base = args.requiredString("base"),
                    quote = args.requiredString("quote"),
                    source = args["source"]?.asText(),
                )
                "clearing_list_batches" -> clearingClient.listBatches(
                    status = args["status"]?.asText(),
                    page = 0,
                    size = args["size"]?.asInt() ?: 20,
                )
                "clearing_get_batch" -> clearingClient.getBatch(args.requiredString("batchId"))
                "clearing_get_batch_items" -> clearingClient.getBatchItems(args.requiredString("batchId"))
                "interest_list_accruals" -> interestClient.listAccruals()
                "interest_get_accruals" -> interestClient.getAccruals(
                    accountId = args.requiredString("accountId"),
                    from = args["from"]?.asText(),
                    to = args["to"]?.asText(),
                )
                "interest_accrual_summary" -> interestClient.getSummary(
                    accountId = args.requiredString("accountId"),
                    from = args["from"]?.asText() ?: "",
                    to = args["to"]?.asText() ?: "",
                )
                "dispute_list" -> disputeClient.list(args["status"]?.asText())
                "dispute_get" -> disputeClient.get(args.requiredString("disputeId"))
                "dispute_list_by_account" -> disputeClient.listByAccount(args.requiredString("accountId"))
                "dispute_get_timeline" -> disputeClient.getTimeline(args.requiredString("disputeId"))
                "sepa_instant_list" -> sepaInstantClient.listPayments()
                "sepa_instant_get" -> sepaInstantClient.getPayment(args.requiredString("paymentId"))
                "sepa_instant_list_by_debtor" -> sepaInstantClient.listByDebtor(
                    debtorAccountId = args.requiredString("debtorAccountId"),
                    page = 0,
                    size = 20,
                )
                "query_metrics" -> {
                    // Range query when both bounds are given; otherwise an instant query.
                    val query = args.requiredString("query")
                    val start = args["start"]?.asText()
                    val end = args["end"]?.asText()
                    if (!start.isNullOrBlank() && !end.isNullOrBlank()) {
                        prometheusClient.queryRange(query, start, end, args["step"]?.asText() ?: "60s")
                    } else {
                        prometheusClient.query(query, args["time"]?.asText())
                    }
                }
                "query_loki_logs" -> lokiClient.queryRange(
                    query = args.requiredString("query"),
                    start = args["start"]?.asText(),
                    end = args["end"]?.asText(),
                    // Cap the line count so a broad selector can't pull an unbounded result set.
                    limit = (args["limit"]?.asInt() ?: 100).coerceIn(1, MAX_LOKI_LINES),
                    direction = "backward",
                )
                "list_alerts" -> alertmanagerClient.listAlerts(
                    active = true,
                    silenced = false,
                    filter = args["filter"]?.asText(),
                )
                "flip_feature_flag" -> {
                    // Propose a feature-flag flip (ADR-0067 / issue #419).
                    //
                    // Safety invariants (mirror OPA rest.rego `prohibited` rule):
                    //   1. Prohibited safety-control keys are rejected outright — no approval can
                    //      override them (SCA, sanctions, AML, fail-closed payment gate).
                    //   2. All other flips create a HITL Proposal with operation=featureflag.flip
                    //      in the AuditEvent, so the OPA four_eyes_required rule can gate approval.
                    //   3. The Proposal has NO runtime effect until a human approves it in the
                    //      admin-ui queue and the gitops ConfigMap is updated.
                    val flagKey = args.requiredString("flagKey")
                    val targetVariant = args.requiredString("targetVariant")
                    val rationale = args.requiredString("rationale")

                    // Prohibited keys (mirrors rules.yaml:feature_flags.prohibited_flag_combinations
                    // and OPA rest.rego `prohibited` rule — kept in sync by the CI gate).
                    val prohibitedKeys = setOf(
                        "sca-enforcement-disabled",
                        "sanctions-screening-disabled",
                        "aml-screening-disabled",
                        "payment-gate-fail-open",
                    )
                    if (flagKey in prohibitedKeys) {
                        runBlocking {
                            auditPublisher.publish(
                                AuditEvent(
                                    actorId = actorId,
                                    actorType = "AI_AGENT",
                                    operation = "featureflag.flip",
                                    resourceType = "feature-flag",
                                    resourceId = flagKey,
                                    result = AuditResult.FAILURE,
                                    payload = mapOf("targetVariant" to targetVariant, "reason" to "prohibited"),
                                ),
                            )
                        }
                        return ToolCallResult(
                            content = listOf(
                                ToolContent(
                                    text = "Flag '$flagKey' is a prohibited safety control (ADR-0067/OPA). " +
                                        "This flag may never be flipped — it guards SCA, sanctions/AML " +
                                        "screening, or the fail-closed payment gate.",
                                ),
                            ),
                            isError = true,
                        )
                    }

                    val p = proposalService.create(
                        title = "Feature-flag flip: $flagKey → $targetVariant",
                        rationale = rationale,
                        suggestedAction = "In the flagd ConfigMap for the owning service, set flag " +
                            "'$flagKey' defaultVariant to '$targetVariant'. Verify behaviour in staging " +
                            "before approving for production.",
                        proposedBy = actorId,
                        modelId = null,
                        correlationId = null,
                    )

                    // ADR-0067 §5/§7: emit AuditEvent with operation=featureflag.flip so the OPA
                    // four_eyes_required rule is visible in the audit trail and the admin-ui can
                    // surface the approval requirement alongside the proposal.
                    runBlocking {
                        auditPublisher.publish(
                            AuditEvent(
                                actorId = actorId,
                                actorType = "AI_AGENT",
                                operation = "featureflag.flip",
                                resourceType = "feature-flag",
                                resourceId = flagKey,
                                result = AuditResult.SUCCESS,
                                payload = mapOf(
                                    "targetVariant" to targetVariant,
                                    "proposalId" to p.id.toString(),
                                    "state" to p.state.name,
                                ),
                            ),
                        )
                    }

                    objectMapper.createObjectNode()
                        .put("status", "proposed")
                        .put("flagKey", flagKey)
                        .put("targetVariant", targetVariant)
                        .put("proposalId", p.id.toString())
                        .put("state", p.state.name)
                        .put(
                            "message",
                            "Flag flip proposal recorded (HITL, ADR-0067). It has NO runtime effect " +
                                "until a human approves it in the admin-ui approval queue. " +
                                "MONEY_PATH flags require a second approver before gitops ConfigMap is updated.",
                        )
                }

                "draft_ticket" -> {
                    // Materialise a proposal into the HITL queue (ADR-0031 D4). No side effect — it
                    // sits PROPOSED until a human approves. Runs on the worker thread (McpEndpoint is
                    // blocking), so the imperative @Transactional store is safe to call here.
                    val p = proposalService.create(
                        title = args.requiredString("title"),
                        rationale = args.requiredString("rationale"),
                        suggestedAction = args.requiredString("suggested_action"),
                        proposedBy = actorId,
                        modelId = null,
                        correlationId = null,
                    )
                    objectMapper.createObjectNode()
                        .put("status", "proposed")
                        .put("proposalId", p.id.toString())
                        .put("state", p.state.name)
                        .put(
                            "message",
                            "Proposal recorded for human review (HITL). It has NO effect until a human approves it in the admin-ui approval queue.",
                        )
                }
                else -> return ToolCallResult(
                    content = listOf(ToolContent(text = "Unknown tool: $toolName")),
                    isError = true,
                )
            }
            auditExec(actorId, toolName, AuditResult.SUCCESS)
            ToolCallResult(content = listOf(ToolContent(text = objectMapper.writeValueAsString(result))))
        } catch (e: IllegalArgumentException) {
            auditExec(actorId, toolName, AuditResult.FAILURE, "invalid_params")
            ToolCallResult(content = listOf(ToolContent(text = "Invalid parameters: ${e.message}")), isError = true)
        } catch (e: Exception) {
            log.warnf(e, "Tool call failed: %s", toolName)
            auditExec(actorId, toolName, AuditResult.FAILURE, "execution_error")
            ToolCallResult(content = listOf(ToolContent(text = "Tool execution failed: ${e.message}")), isError = true)
        }
    }

    /**
     * AI-attributed audit of a tool *execution outcome* (ADR-0031 D5). Complements
     * [AgentPolicyGate]'s pre-execution ALLOW/DENY decision audit: the gate records whether the
     * call was permitted, this records whether the permitted call actually succeeded. Distinct
     * operation (`agent.mcp.tool_exec`) so the two are not conflated in the audit trail.
     */
    private fun auditExec(actorId: String, toolName: String, result: AuditResult, errorKey: String? = null) {
        val event = AuditEvent(
            actorId = actorId,
            actorType = "AI_AGENT",
            operation = "agent.mcp.tool_exec",
            resourceType = "mcp.tool",
            resourceId = toolName,
            result = result,
            payload = buildMap {
                put("tool", toolName)
                put("outcome", result.name)
                errorKey?.let { put("error", it) }
            },
        )
        runBlocking { auditPublisher.publish(event) }
    }

    private fun JsonNode.requiredString(field: String): String = this[field]?.asText()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Required field '$field' is missing or blank")

    private fun stringProp(description: String) = mapOf("type" to "string", "description" to description)

    // Declared as STRING, not integer, on purpose: llama on Groq frequently emits numeric tool
    // arguments as JSON strings (e.g. {"limit":"5"}), and Groq then rejects the tool call against
    // an `integer` schema with tool_use_failed (HTTP 400). The call() handler coerces with asInt(),
    // which parses "5" -> 5 just fine, so a string schema is both accepted and correct.
    private fun intProp(description: String) = mapOf("type" to "string", "description" to "$description (integer)")

    private fun schema(vararg fields: Pair<String, Pair<Map<String, Any>, Boolean>>): Map<String, Any> {
        val properties = fields.associate { (name, pair) -> name to pair.first }
        val required = fields.filter { it.second.second }.map { it.first }
        return buildMap {
            put("type", "object")
            put("properties", properties)
            if (required.isNotEmpty()) put("required", required)
        }
    }
}
