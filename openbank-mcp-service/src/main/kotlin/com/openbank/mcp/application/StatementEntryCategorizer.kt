// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

/**
 * A FIRST-PASS, RULE-BASED classification of a statement entry's `description`/`counterparty` text
 * into a coarse human-readable category — added so `query.statement.readonly` can answer "why was I
 * charged X" without the calling model having to guess from raw free text alone.
 *
 * This is deliberately NOT the fleet's real merchant categorisation: transaction-service already
 * carries an MCC-derived `merchantCategory` (`Transaction.kt`, ADR-0103) for card spend, but
 * statement-service's own `StatementEntry` (the source this tool reads) never carries it — its
 * `TransactionDto` client mapping drops the field, and statement-service's byte-identical-projection
 * invariant (ADR-0035) makes widening that model a change to a service outside this PR's declared
 * scope. Wiring the real MCC category through is a follow-up (issue #4109 references it); until then
 * this keyword match over `description`/`counterparty` is what it is — a heuristic, not ML, and
 * wrong often enough that a calling model should treat it as a hint, never a fact. Framework-free by
 * design (pure string matching) so it needs no test double to exercise.
 */
object StatementEntryCategorizer {

    /** Best-effort category for one entry's free text; never throws, always returns something. */
    fun categorize(description: String?, counterparty: String?): String {
        val text = listOfNotNull(description, counterparty).joinToString(" ").lowercase()
        return RULES.firstOrNull { (_, keywords) -> keywords.any { text.contains(it) } }?.first ?: "OTHER"
    }

    // Ordered: the first matching category wins, so a more specific keyword set should sit above a
    // broader one. Keywords are intentionally generic (language-agnostic where possible) rather than
    // an exhaustive merchant list — see the class KDoc on why this stays a heuristic.
    private val RULES: List<Pair<String, List<String>>> = listOf(
        "FEES" to listOf("fee", "poplatek", "charge", "maintenance"),
        "INTEREST" to listOf("interest", "urok", "úrok"),
        "SALARY_INCOME" to listOf("salary", "wage", "mzda", "payroll"),
        "TRANSFER" to listOf("transfer", "prevod", "převod", "standing order", "trvaly prikaz"),
        "GROCERIES" to listOf("grocery", "groceries", "supermarket", "potraviny", "market"),
        "UTILITIES" to listOf("utility", "utilities", "electricity", "energie", "elektrina", "water", "gas"),
        "TELECOM" to listOf("telecom", "mobile", "phone", "internet", "telco"),
        "SUBSCRIPTION" to listOf("subscription", "predplatne", "předplatné"),
        "TRAVEL" to listOf("airline", "hotel", "travel", "flight"),
        "ATM_WITHDRAWAL" to listOf("atm", "withdrawal", "vyber", "výběr"),
    )
}
