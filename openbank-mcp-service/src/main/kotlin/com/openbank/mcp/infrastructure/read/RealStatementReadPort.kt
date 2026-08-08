// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.mcp.application.StatementEntryCategorizer
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.StatementReadPort
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * The real read adapter behind [StatementReadPort] (issue #4109, ADR-0248), following
 * [RealAccountReadPort]'s pattern exactly: live-validate the presented consent (via [ConsentGate])
 * before touching downstream data, then resolve the caller-given IBAN to the account-service UUID
 * statement-service is keyed by.
 *
 * Two things this adapter does that [RealAccountReadPort]'s reads do not:
 *  - When [legalSequence] is omitted it resolves "the statement" from statement-service's own
 *    period list — the most recent CLOSED period, filtered to [StatementReadPort.getStatementSummary]'s
 *    `currency` when given. A caller asking "summarize my March statement" supplies a period, not a
 *    legal sequence number.
 *  - It enriches each entry with a best-effort [StatementEntryCategorizer] category — a heuristic,
 *    not a claim of real merchant categorisation (see that class's KDoc for why).
 */
@ApplicationScoped
class RealStatementReadPort(
    @RestClient private val consent: ConsentValidateClient,
    @RestClient private val accounts: AccountServiceClient,
    @RestClient private val statements: StatementServiceClient,
) : StatementReadPort {

    private val gate = ConsentGate(consent)

    override fun getStatementSummary(
        consentContext: ConsentContext,
        accountId: String,
        currency: String?,
        legalSequence: Long?,
    ): JsonNode {
        gate.validate(consentContext, ConsentScopes.STATEMENTS_READ, accountIban = accountId)
        val internalId = UUID.fromString(accounts.getAccountByIban(accountId).path("id").asText())

        val (resolvedCurrency, resolvedSequence) = if (legalSequence != null) {
            val ccy = requireNotNull(currency) { "currency is required when legalSequence is given" }
            ccy to legalSequence
        } else {
            latestClosedPeriod(internalId, currency)
        }

        val summary = statements.statementSummary(internalId, resolvedCurrency, resolvedSequence)
        return withCategories(summary)
    }

    /** The most recent CLOSED period for [accountId], optionally narrowed to one pocket [currency]. */
    private fun latestClosedPeriod(accountId: UUID, currency: String?): Pair<String, Long> {
        val periods = statements.listStatements(accountId)
        val candidate = periods.asSequence()
            .filter { it.path("status").asText() == "CLOSED" }
            .filter { currency == null || it.path("pocketCurrency").asText() == currency }
            .maxByOrNull { it.path("legalSequenceNumber").asLong() }
            ?: error(
                "no closed statement period found for account '$accountId'" +
                    (currency?.let { " currency '$it'" } ?: ""),
            )
        return candidate.path("pocketCurrency").asText() to candidate.path("legalSequenceNumber").asLong()
    }

    /** Adds a best-effort [StatementEntryCategorizer.categorize] `category` field to every entry. */
    private fun withCategories(summary: JsonNode): JsonNode {
        val root = summary.deepCopy<ObjectNode>()
        val entries = root.path("entries")
        if (entries is ArrayNode) {
            entries.forEach { entry ->
                if (entry is ObjectNode) {
                    val category = StatementEntryCategorizer.categorize(
                        entry.path("description").takeIf { it.isTextual }?.asText(),
                        entry.path("counterparty").takeIf { it.isTextual }?.asText(),
                    )
                    entry.put("category", category)
                }
            }
        }
        return root
    }
}
