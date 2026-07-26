// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.domain.account.Iban
import java.math.BigDecimal
import java.util.Currency

/**
 * Server-side validation of `propose_payment`'s arguments — threat model T-T2 (#2414).
 *
 * The tool advertises an `inputSchema`, and that is **advertisement, not enforcement**: MCP clients
 * are not obliged to honour it, and the caller here is a model that composes its own arguments. So
 * every field arrived unchecked, straight through to the port.
 *
 * That is tolerable only while [com.openbank.mcp.infrastructure.read.StubProposalPort] is the
 * binding, because a stub cannot act on a malformed amount. #2414's remaining work — a real
 * `PROPOSED`-only state machine behind the port — needs its own design and is blocked on
 * copilot-service's `ActionProposal` not being a cross-service endpoint. Validation is NOT blocked
 * on that: it belongs at the tool boundary regardless of what the port turns out to be, and doing
 * it now means the eventual real implementation inherits it instead of being the first thing to
 * meet an unvalidated amount.
 *
 * Throws [IllegalArgumentException] on any violation. `McpEndpoint` already maps that to JSON-RPC
 * `INVALID_PARAMS` and audits it as `FAILURE / "invalid params"`, so a rejection is a proper
 * protocol error with an audit trail rather than a generic tool error.
 *
 * Deliberately NOT here: whether `fromAccountId` is inside the presented consent. That is the port's
 * job — it holds the consent context — and duplicating it would create two answers to one question.
 */
internal object ProposePaymentArgs {

    /** SEPA IBANs are 15-34 chars; the shared value object does the mod-97 check (ADR-0011). */
    fun validate(args: JsonNode) {
        val fromAccountId = args.path("fromAccountId").takeIf { it.isTextual }?.asText()?.trim()
        require(!fromAccountId.isNullOrEmpty()) { "fromAccountId is required" }

        val toIban = args.path("toIban").takeIf { it.isTextual }?.asText()?.trim()
        require(!toIban.isNullOrEmpty()) { "toIban is required" }
        // Reuse openbank-libs-domain's Iban rather than a second regex: one mod-97 implementation,
        // and it is the one the payment services already validate against.
        require(Iban.isValid(toIban)) { "toIban is not a valid IBAN" }

        val currency = args.path("currency").takeIf { it.isTextual }?.asText()?.trim()
        require(!currency.isNullOrEmpty()) { "currency is required" }
        require(currency.length == CURRENCY_CODE_LENGTH && currency.all { it in 'A'..'Z' }) {
            "currency must be a 3-letter uppercase ISO 4217 code"
        }
        require(runCatching { Currency.getInstance(currency) }.isSuccess) {
            "currency is not a known ISO 4217 code"
        }

        // The amount is a STRING in the schema on purpose — a JSON number would already have been
        // through a double somewhere upstream, and money does not survive that.
        val rawAmount = args.path("amount").takeIf { it.isTextual }?.asText()?.trim()
        require(!rawAmount.isNullOrEmpty()) { "amount is required" }
        // Reject scientific notation, signs and separators explicitly: BigDecimal("1E3") parses
        // happily, and an amount that reads as 1E3 in an audit log is not a reviewable proposal.
        require(PLAIN_DECIMAL.matches(rawAmount)) {
            "amount must be a plain decimal string, e.g. 12.34 (no sign, exponent or separator)"
        }
        val amount = BigDecimal(rawAmount)
        require(amount > BigDecimal.ZERO) { "amount must be greater than zero" }
        // ISO 4217 minor units: 0-3 across live currencies. Anything finer is not payable and would
        // be silently rounded by whatever executes it — the classic place a cent goes missing.
        require(amount.scale() <= MAX_MINOR_UNIT_DIGITS) {
            "amount has more decimal places than any ISO 4217 currency has minor units"
        }
    }

    private const val CURRENCY_CODE_LENGTH = 3
    private const val MAX_MINOR_UNIT_DIGITS = 3
    private val PLAIN_DECIMAL = Regex("""\d{1,18}(\.\d{1,4})?""")
}
