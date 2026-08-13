// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.port.`in`.CreateProposalUseCase
import com.openbank.agent.application.port.out.DownstreamReadPort
import com.openbank.agent.domain.ToolCallResult
import com.openbank.agent.domain.ToolContent
import com.openbank.agent.domain.ToolDefinition
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger

/**
 * The MCP tool catalog and its governance (ADR-0031). This class owns what the model may be
 * offered, which charter capability each tool needs, the two HITL proposal tools, and the
 * AI-attributed audit of every execution outcome.
 *
 * It owns no transport: the read tools resolve through [DownstreamReadPort], so adding a downstream
 * service never touches this file and the application layer never sees a REST client (ADR-0002).
 * Proposals are created through [CreateProposalUseCase] only — the narrowest inbound port, which by
 * construction cannot decide a proposal the loop just drafted.
 */
@ApplicationScoped
// One function per governance concern (catalog lookups, dispatch, the two HITL proposal tools, the
// two audit shapes, schema builders). They are small and cohesive; the transport that used to bulk
// this class out now lives in the adapter, and splitting the rest would scatter the catalog.
@Suppress("TooManyFunctions")
class McpToolRegistry {

    @Inject lateinit var downstream: DownstreamReadPort

    @Inject lateinit var proposals: CreateProposalUseCase

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var auditPublisher: AuditEventPublisher

    /**
     * Charter lookup for AI attribution (ADR-0031 D5, issue #3667). The acting model id is derived
     * from the actor's charter rather than threaded through every dispatch signature: the charter is
     * the declaration of record for which model an agent runs, and it is keyed by exactly the
     * `actorId` this class already carries. An unregistered actor yields
     * [CharterRegistry.UNKNOWN_MODEL] — the attribution is then explicitly unknown, never absent.
     */
    @Inject lateinit var charters: CharterRegistry

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
            name = "get_catalog_revision",
            description = "Get one exact v2 catalog revision for grounded draft review. Read-only; " +
                "it cannot create, replace, publish or retire a revision.",
            inputSchema = schema(
                "offeringId" to (stringProp("UUID of the offering that owns the revision") to true),
                "revisionId" to (stringProp("UUID of the exact catalog revision") to true),
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
        "get_catalog_revision" to "query.catalog.readonly",
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

    /**
     * Dispatch one MCP tool call: the two HITL proposal tools are handled here (they are governance,
     * not data access), everything else is a downstream read resolved through [downstream].
     * Every outcome is audited exactly once (ADR-0031 D5).
     */
    fun call(toolName: String, arguments: JsonNode?, actorId: String = "unknown"): ToolCallResult {
        val args = arguments ?: objectMapper.createObjectNode()
        return try {
            when {
                toolName == "flip_feature_flag" -> flipFeatureFlag(args, actorId)
                toolName == "draft_ticket" -> succeed(toolName, actorId, draftTicket(args, actorId))
                // Every remaining registered tool is a downstream READ; the adapter owns which
                // service it reaches and how the arguments marshal onto that call.
                downstream.handles(toolName) -> succeed(toolName, actorId, downstream.read(toolName, args))
                else -> ToolCallResult(
                    content = listOf(ToolContent(text = "Unknown tool: $toolName")),
                    isError = true,
                )
            }
        } catch (e: IllegalArgumentException) {
            auditExec(actorId, toolName, AuditResult.FAILURE, "invalid_params")
            ToolCallResult(content = listOf(ToolContent(text = "Invalid parameters: ${e.message}")), isError = true)
        } catch (e: Exception) {
            log.warnf(e, "Tool call failed: %s", toolName)
            auditExec(actorId, toolName, AuditResult.FAILURE, "execution_error")
            ToolCallResult(content = listOf(ToolContent(text = "Tool execution failed: ${e.message}")), isError = true)
        }
    }

    /** Audit the successful execution and hand the document to the model as text. */
    private fun succeed(toolName: String, actorId: String, result: JsonNode): ToolCallResult {
        auditExec(actorId, toolName, AuditResult.SUCCESS)
        return ToolCallResult(content = listOf(ToolContent(text = objectMapper.writeValueAsString(result))))
    }

    /**
     * Propose a feature-flag flip (ADR-0067 / issue #419).
     *
     * Safety invariants (mirror the OPA `rest.rego` `prohibited` rule):
     *   1. Prohibited safety-control keys are rejected outright — no approval can override them
     *      (SCA, sanctions, AML, fail-closed payment gate).
     *   2. All other flips create a HITL proposal and emit `operation=featureflag.flip`, so the OPA
     *      four_eyes_required rule can gate the approval.
     *   3. The proposal has NO runtime effect until a human approves it in the admin-ui queue and
     *      the gitops ConfigMap is updated.
     */
    private fun flipFeatureFlag(args: JsonNode, actorId: String): ToolCallResult {
        val flagKey = args.requiredString("flagKey")
        val targetVariant = args.requiredString("targetVariant")
        val rationale = args.requiredString("rationale")

        if (flagKey in PROHIBITED_FLAG_KEYS) {
            auditFlip(
                actorId,
                flagKey,
                AuditResult.FAILURE,
                mapOf(
                    "targetVariant" to targetVariant,
                    "reason" to "prohibited",
                ),
            )
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

        val p = proposals.create(
            title = "Feature-flag flip: $flagKey → $targetVariant",
            rationale = rationale,
            suggestedAction = "In the flagd ConfigMap for the owning service, set flag " +
                "'$flagKey' defaultVariant to '$targetVariant'. Verify behaviour in staging " +
                "before approving for production.",
            proposedBy = actorId,
            // ADR-0031 D5 (#3667): an MCP-drafted proposal is AI-attributed, so it records the
            // acting agent's charter-declared model rather than a null the audit cannot interpret.
            modelId = charters.modelId(actorId),
            correlationId = null,
        )
        auditFlip(
            actorId,
            flagKey,
            AuditResult.SUCCESS,
            mapOf("targetVariant" to targetVariant, "proposalId" to p.id.toString(), "state" to p.state.name),
        )

        val body = objectMapper.createObjectNode()
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
        return succeed("flip_feature_flag", actorId, body)
    }

    /**
     * Materialise a proposal into the HITL queue (ADR-0031 D4). No side effect — it sits PROPOSED
     * until a human approves. Runs on the worker thread (McpEndpoint is blocking), so the imperative
     * store is safe to call here.
     */
    private fun draftTicket(args: JsonNode, actorId: String): JsonNode {
        val p = proposals.create(
            title = args.requiredString("title"),
            rationale = args.requiredString("rationale"),
            suggestedAction = args.requiredString("suggested_action"),
            proposedBy = actorId,
            // ADR-0031 D5 (#3667): an MCP-drafted proposal is AI-attributed, so it records the
            // acting agent's charter-declared model rather than a null the audit cannot interpret.
            modelId = charters.modelId(actorId),
            correlationId = null,
        )
        return objectMapper.createObjectNode()
            .put("status", "proposed")
            .put("proposalId", p.id.toString())
            .put("state", p.state.name)
            .put(
                "message",
                "Proposal recorded for human review (HITL). It has NO effect until a human " +
                    "approves it in the admin-ui approval queue.",
            )
    }

    /** ADR-0067 §5/§7: the flip's own audit event, distinct from the tool-execution outcome. */
    private fun auditFlip(actorId: String, flagKey: String, result: AuditResult, payload: Map<String, Any?>) {
        runBlocking {
            auditPublisher.publish(
                AuditEvent(
                    actorId = actorId,
                    actorType = "AI_AGENT",
                    operation = "featureflag.flip",
                    resourceType = "feature-flag",
                    resourceId = flagKey,
                    result = result,
                    // AI attribution (ADR-0031 D5, #3667). A caller-supplied model_id is never
                    // overwritten; today no caller supplies one, so the charter value applies.
                    payload = payload + ("model_id" to (payload["model_id"] ?: charters.modelId(actorId))),
                ),
            )
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
                put("model_id", charters.modelId(actorId))
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

    private companion object {
        /**
         * Flags no approval can ever flip. Mirrors `rules.yaml:feature_flags` and the OPA
         * `rest.rego` `prohibited` rule — kept in sync by the CI gate.
         */
        val PROHIBITED_FLAG_KEYS = setOf(
            "sca-enforcement-disabled",
            "sanctions-screening-disabled",
            "aml-screening-disabled",
            "payment-gate-fail-open",
        )
    }
}
