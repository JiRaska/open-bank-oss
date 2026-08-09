// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.MarketingReachPort
import com.openbank.mcp.application.port.out.PaymentConfirmationReadPort
import com.openbank.mcp.application.port.out.ProposalPort
import com.openbank.mcp.application.port.out.StatementReadPort
import com.openbank.mcp.application.protocol.ToolCallResult
import com.openbank.mcp.application.protocol.ToolContent
import com.openbank.mcp.application.protocol.ToolDefinition
import jakarta.enterprise.context.ApplicationScoped

/**
 * The curated MCP tool set (ADR-0181): consent-scoped reads plus a single HITL payment proposal.
 * Mirrors agent-service's McpToolRegistry — the [capabilities] map is the deny-by-default gate: a
 * tool with no capability entry has no OPA action to authorize and is refused. The capability
 * strings are the `agents.yaml` charter actions the ADR-0034 PDP evaluates as `input.action` for an
 * `AI_AGENT` principal (see McpEndpoint's @Authorize bridge).
 */
@ApplicationScoped
class McpToolRegistry(
    private val accounts: AccountReadPort,
    private val statements: StatementReadPort,
    private val paymentConfirmations: PaymentConfirmationReadPort,
    private val proposals: ProposalPort,
    private val marketingReach: MarketingReachPort,
    private val masker: McpPiiMasker,
    private val mapper: ObjectMapper,
) {

    /** tool name -> the OPA charter capability the PDP gates the call on. Absent = refused. */
    val capabilities: Map<String, String> = mapOf(
        "list_accounts" to "query.account.readonly",
        "get_balance" to "query.balance.readonly",
        "list_transactions" to "query.transaction.readonly",
        "list_consents" to "query.consent.readonly",
        "get_statement" to "query.statement.readonly",
        "get_payment_confirmation" to "query.payment_confirmation.readonly",
        "propose_payment" to "propose.payment",
        // ADR-0209 D5. No charter carries this capability yet, so the PDP denies every call — that is
        // the intended state, not an omission: the grant is a separate change (agents.yaml charter +
        // tool_tiers + rego), and `McpToolRegistryTest` asserts the denial rather than assuming it.
        // Registering the capability here is what lets the call REACH the PDP at all; without the
        // entry McpEndpoint refuses earlier, with "no capability mapping", and the policy would never
        // be exercised.
        "count_marketing_consents" to "query.marketing.readonly",
    )

    val tools: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "list_accounts",
            description = "List the accounts the presented PSD2 consent grants access to.",
            inputSchema = obj(mapOf<String, Any>(), required = emptyList()),
            service = "openbank-account-service",
            domain = "accounts",
        ),
        ToolDefinition(
            name = "get_balance",
            description = "Get the balance of a granted account.",
            inputSchema = obj(
                mapOf("accountId" to strProp("Account id (must be within the consent)")),
                listOf("accountId"),
            ),
            service = "openbank-balance-service",
            domain = "accounts",
        ),
        ToolDefinition(
            name = "list_transactions",
            description = "List recent transactions of a granted account.",
            inputSchema = obj(
                mapOf(
                    "accountId" to strProp("Account id (must be within the consent)"),
                    "limit" to mapOf("type" to "integer", "description" to "Max rows (default 50)"),
                ),
                listOf("accountId"),
            ),
            service = "openbank-transaction-service",
            domain = "transactions",
        ),
        ToolDefinition(
            name = "list_consents",
            description = "List the PSD2 consents the acting agent holds.",
            inputSchema = obj(mapOf<String, Any>(), required = emptyList()),
            service = "openbank-consent-service",
            domain = "consent",
        ),
        ToolDefinition(
            name = "get_statement",
            description =
            "Get a closed account statement as STRUCTURED DATA (period, opening/closing balance, " +
                "the itemized entry list with a best-effort `category` per entry) — use this to " +
                "answer 'summarize my March statement', 'what did I spend on groceries last month' " +
                "or 'why was I charged X' by reasoning over the entries yourself; this tool returns " +
                "data, not a rendered document or a written summary. Pass `legalSequence` (with " +
                "`currency`) for one exact statement, or omit both to get the most recently closed " +
                "one for the account (optionally narrowed to `currency`). `category` is a rule-based " +
                "heuristic over each entry's description/counterparty — treat it as a hint, not fact. " +
                "IBANs and counterparty names come back masked (only the account's own last 4 digits " +
                "are kept) — amounts, dates, currency and category are not, and are enough to explain " +
                "the statement.",
            inputSchema = obj(
                mapOf(
                    "accountId" to strProp("Account id (must be within the consent)"),
                    "currency" to strProp("ISO 4217 pocket currency; required together with legalSequence"),
                    "legalSequence" to
                        mapOf("type" to "integer", "description" to "Exact statement sequence; omit for the latest"),
                ),
                listOf("accountId"),
            ),
            service = "openbank-statement-service",
            domain = "statements",
        ),
        ToolDefinition(
            name = "get_payment_confirmation",
            description =
            "Get the confirmation details of a payment you already made — reference/end-to-end id, " +
                "execution/settlement date, amount, currency, debtor/creditor account, payee name, " +
                "remittance/reference text and status. Works for either a SEPA or a domestic (CZK) " +
                "payment; you do not need to know which rail it went on. Use this to answer 'did my " +
                "payment to X go through', 'when did payment Y settle' or to confirm the details of " +
                "a specific past payment by its id — it does not list or search payments. IBANs and " +
                "payee/payer names come back masked, same as every other tool on this surface.",
            inputSchema = obj(
                mapOf("paymentId" to strProp("The payment id (as returned when the payment was created)")),
                listOf("paymentId"),
            ),
            // No single value: this tool reaches whichever of openbank-sepa-payment /
            // openbank-domestic-payment actually holds [paymentId] (see the port KDoc) — a single
            // string here would misrepresent the other rail rather than describe both accurately.
            service = null,
            domain = "payments",
        ),
        ToolDefinition(
            name = "count_marketing_consents",
            description =
            "Count ACTIVE marketing consents per scope (campaign reach). Returns COUNTS ONLY — " +
                "never party ids, names or contact details. Who receives anything is decided by " +
                "campaign-service under consent-gated delivery, not here.",
            inputSchema = obj(mapOf<String, Any>(), required = emptyList()),
            service = "openbank-consent-service",
            domain = "consent",
        ),
        ToolDefinition(
            name = "propose_payment",
            // Says UNAVAILABLE in the advertisement, not only in the error (#2414). A model picks
            // its tool from this text; one that reads "create a proposal" will call it, narrate the
            // intent to a person, and only then meet the refusal. Kept advertised rather than
            // withdrawn so the charter/PDP/audit path is still exercised on every attempt.
            description =
            "UNAVAILABLE — no proposal store is wired, so this call is refused and records nothing. " +
                "When implemented it creates a REVIEWABLE payment proposal (never a debit; a human " +
                "+ SCA disposes).",
            inputSchema = obj(
                mapOf(
                    "fromAccountId" to strProp("Debtor account (must be within the consent)"),
                    "toIban" to strProp("Creditor IBAN"),
                    "amount" to mapOf("type" to "string", "description" to "Amount, decimal string"),
                    "currency" to strProp("ISO 4217 currency"),
                ),
                listOf("fromAccountId", "toIban", "amount", "currency"),
            ),
            service = "openbank-mcp-service",
            domain = "payments",
        ),
    )

    /** Execute one tool. The caller (McpEndpoint) has already OPA-authorized [toolName]. */
    fun call(toolName: String, arguments: JsonNode, ctx: ConsentContext): ToolCallResult {
        val result: JsonNode = when (toolName) {
            "list_accounts" -> accounts.listAccounts(ctx)
            "get_balance" -> accounts.getBalance(ctx, arguments.reqText("accountId"))
            "list_transactions" ->
                accounts.listTransactions(
                    ctx,
                    arguments.reqText("accountId"),
                    arguments.path("limit").asInt(DEFAULT_TX_LIMIT),
                )
            "list_consents" -> accounts.listConsents(ctx)
            "get_statement" -> statements.getStatementSummary(
                ctx,
                arguments.reqText("accountId"),
                arguments.path("currency").takeIf { it.isTextual }?.asText(),
                arguments.path("legalSequence").takeIf { it.isIntegralNumber }?.asLong(),
            )
            "get_payment_confirmation" -> paymentConfirmations.getPaymentConfirmation(
                ctx,
                arguments.reqText("paymentId"),
            )
            // `ctx` is deliberately NOT passed: this is an operator-plane aggregate with no consent to
            // intersect against, and MarketingReachPort's signature says so. See its kdoc before
            // "fixing" the inconsistency.
            "count_marketing_consents" -> marketingReach.countMarketingConsents()
            // The PROPOSED-only invariant is enforced HERE, on the call path, not left to whichever
            // ProposalPort is bound (T-E4, #2414). See ProposedOnly for why it is a whitelist of one.
            "propose_payment" -> {
                // Validate BEFORE the port sees anything (T-T2, #2414). The advertised inputSchema
                // is advertisement, not enforcement — the caller is a model composing its own
                // arguments, and an MCP client is not obliged to honour the schema.
                ProposePaymentArgs.validate(arguments)
                ProposedOnly.enforce(proposals.proposePayment(ctx, arguments))
            }
            else -> return ToolCallResult(listOf(ToolContent(text = "Unknown tool: $toolName")), isError = true)
        }
        // Two orthogonal controls, both applied HERE — one response-shaping step every tool passes
        // through, so a new tool cannot forget either of them (#2412).
        //   masker  — the charter's `data_scope.pii: masked` (agents.yaml, `mcp-anonymous`):
        //             narrows WHAT can leave the bank.
        //   wrap    — instruction/data separation (ADR-0195 T-I3): narrows what the data that DOES
        //             leave can make the calling client's model do. See McpUntrustedData.
        // Order matters only in that wrapping must come last: it must enclose the final bytes, or
        // a later step could re-introduce text outside the markers.
        val masked = mapper.writeValueAsString(masker.mask(result))
        return ToolCallResult(listOf(ToolContent(text = McpUntrustedData.wrap(masked))))
    }

    private fun JsonNode.reqText(field: String): String = path(field).takeIf { it.isTextual }?.asText()
        ?: throw IllegalArgumentException("missing or non-string argument: $field")

    private fun strProp(desc: String) = mapOf("type" to "string", "description" to desc)

    private fun obj(props: Map<String, Any>, required: List<String>): Map<String, Any> = buildMap {
        put("type", "object")
        put("properties", props)
        if (required.isNotEmpty()) put("required", required)
    }

    private companion object {
        const val DEFAULT_TX_LIMIT = 50
    }
}
