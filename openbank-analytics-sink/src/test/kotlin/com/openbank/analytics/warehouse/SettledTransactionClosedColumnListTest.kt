// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.warehouse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The post-settlement transaction fact must never carry a counterparty name or a free-text message
 * (ADR-0282 phase 1, issue #8792 acceptance criterion 3).
 *
 * WHY A TEST AND NOT THE COMMENT THAT WAS ALREADY THERE. `V13__settled_transactions.sql` states the
 * guarantee in prose — "this view selects a closed column list: no `payload` passthrough, so a field
 * added to the event later cannot arrive here by accident". That sentence is true today and nothing
 * re-checks it, which is the shape this repo has been bitten by repeatedly: a comment asserting
 * current structure goes stale exactly like a report asserting current remote state. The issue asks
 * for "a test that fails if either field is added later", so the control has to be executable.
 *
 * WHAT IT ASSERTS, AND WHY IT IS AN ALLOWLIST. A denylist of forbidden names cannot work: the field
 * this is meant to stop could arrive as `counterpartyName`, `remitterName`, `creditorName`,
 * `description`, `narrative` or `unstructuredRemittanceInformation`, and enumerating that set is
 * guessing. An allowlist is decidable — any column not written here fails, so ADDING one is a
 * deliberate act that edits this list and re-reads the criterion, which is exactly the pause the
 * criterion is asking for.
 *
 * THE VACUOUS-PASS TRAP THIS AVOIDS. An allowlist over parsed aliases is silently defeated by
 * `SELECT *`: the parser finds no aliases, the comparison has nothing to disagree with, and the test
 * passes while the view forwards every column of `bronze_events` — payload included. So `*` is
 * rejected explicitly, and the parse is asserted non-empty rather than merely equal. A test that
 * cannot detect the absence of what it tests is decoration.
 */
class SettledTransactionClosedColumnListTest {

    /**
     * Exactly the columns V13 projects, and nothing else. Changing this list is the deliberate act
     * described above — if you are adding a counterparty name or a message field here, criterion 3
     * of #8792 says do not.
     */
    private val settledTransactionColumns = setOf(
        "transaction_id",
        "settled_at",
        "settled_month",
        "amount",
        "currency_code",
        "transaction_type",
        "instruction_type",
        "rail",
        "source_account_id",
        "target_account_id",
        "initiated_by_party_id",
        "initiated_at",
    )

    private val partySettledSpendColumns = setOf(
        "party_id",
        "settled_month",
        "outbound_count",
        "outbound_amount",
        "inbound_count",
        "inbound_amount",
        "settled_transactions",
    )

    @Test
    fun `silver_settled_transactions projects exactly the declared closed column list`() {
        val columns = projectedColumnsOf(MIGRATION.readText(), "silver_settled_transactions")

        assertThat(columns)
            .describedAs(
                "V13's silver_settled_transactions column list changed. If a column was added, " +
                    "#8792 criterion 3 forbids a counterparty name and any free-text message; " +
                    "anything else belongs in the allowlist in this test, deliberately.",
            )
            .isEqualTo(settledTransactionColumns)
    }

    @Test
    fun `gold_party_settled_spend projects exactly the declared closed column list`() {
        val columns = projectedColumnsOf(MIGRATION.readText(), "gold_party_settled_spend")

        assertThat(columns).isEqualTo(partySettledSpendColumns)
    }

    /**
     * The second half of V13's own guarantee. A closed alias list is only closed while nothing
     * forwards the raw event: `payload` reaching the projection would re-admit every field the
     * producer ever adds, counterparty name and message included.
     */
    @Test
    fun `neither view forwards the raw payload or a wildcard`() {
        val sql = MIGRATION.readText()

        listOf("silver_settled_transactions", "gold_party_settled_spend").forEach { view ->
            val body = viewBodyOf(sql, view)
            assertThat(body)
                .describedAs("$view must not select a wildcard — it would forward every bronze column")
                .doesNotContain("SELECT *")
            assertThat(projectedColumnsOf(sql, view))
                .describedAs("$view must not project the raw event payload")
                .doesNotContain("payload")
        }
    }

    /**
     * The parser must be able to FAIL, or the three tests above are decoration. Both fixtures are
     * the real V13 text with one line changed, so a parser that silently returns nothing — the
     * failure mode that would make every assertion above vacuous — is caught here.
     */
    @Test
    fun `the parser detects a counterparty name added to the real migration`() {
        val tampered = MIGRATION.readText().replace(
            "    i.occurred_at                                           AS initiated_at",
            "    JSONExtractString(i.payload, 'counterpartyName')        AS counterparty_name,\n" +
                "    i.occurred_at                                           AS initiated_at",
        )
        assertThat(tampered).isNotEqualTo(MIGRATION.readText())

        val columns = projectedColumnsOf(tampered, "silver_settled_transactions")

        assertThat(columns).contains("counterparty_name")
        assertThat(columns).isNotEqualTo(settledTransactionColumns)
    }

    @Test
    fun `the parser detects a wildcard replacing the closed list`() {
        val tampered = MIGRATION.readText().replace(
            "CREATE OR REPLACE VIEW openbank_analytics.silver_settled_transactions AS\nSELECT\n",
            "CREATE OR REPLACE VIEW openbank_analytics.silver_settled_transactions AS\nSELECT *,\n",
        )
        assertThat(tampered).isNotEqualTo(MIGRATION.readText())

        assertThat(viewBodyOf(tampered, "silver_settled_transactions")).contains("SELECT *")
    }

    @Test
    fun `the parser returns a non-empty list for the real migration`() {
        // Guards the whole file against a rename or a reformat quietly turning every assertion
        // above into a comparison of two empty sets.
        assertThat(MIGRATION).exists()
        assertThat(projectedColumnsOf(MIGRATION.readText(), "silver_settled_transactions")).isNotEmpty()
        assertThat(projectedColumnsOf(MIGRATION.readText(), "gold_party_settled_spend")).isNotEmpty()
    }

    private companion object {
        val MIGRATION = File("src/main/resources/clickhouse/V13__settled_transactions.sql")

        /** The text of one `CREATE OR REPLACE VIEW <name> AS …;` statement. */
        fun viewBodyOf(sql: String, view: String): String {
            val marker = "CREATE OR REPLACE VIEW openbank_analytics.$view AS"
            val start = sql.indexOf(marker)
            require(start >= 0) { "view $view not found in the migration" }
            val end = sql.indexOf(";", start)
            require(end > start) { "unterminated statement for $view" }
            return sql.substring(start, end)
        }

        /**
         * The aliases of the statement's OUTERMOST select list.
         *
         * Nesting is why this counts parentheses rather than matching `AS <name>` everywhere: V13's
         * inner sub-selects carry their own aliases (`AS c`, `AS i`, `'OUT' AS direction`) and those
         * are not output columns. Only depth 0 is the view's shape.
         */
        fun projectedColumnsOf(sql: String, view: String): Set<String> {
            val body = viewBodyOf(sql, view)
            val selectAt = body.indexOf("SELECT")
            val columns = linkedSetOf<String>()
            var depth = 0
            var line = StringBuilder()
            body.substring(selectAt + "SELECT".length).forEach { ch ->
                when (ch) {
                    '(' -> depth++
                    ')' -> depth--
                }
                if (ch == ',' && depth == 0) {
                    aliasOf(line.toString())?.let(columns::add)
                    line = StringBuilder()
                } else {
                    line.append(ch)
                    // The select list ends at the FROM that sits at depth 0.
                    if (depth == 0 && line.toString().trimEnd().endsWith("FROM")) {
                        aliasOf(line.toString().trimEnd().removeSuffix("FROM"))?.let(columns::add)
                        return columns
                    }
                }
            }
            aliasOf(line.toString())?.let(columns::add)
            return columns
        }

        /** `expr AS name` -> `name`; a bare `name` -> itself; a comment-only fragment -> null. */
        fun aliasOf(fragment: String): String? {
            val code = fragment.lines().filterNot { it.trim().startsWith("--") }.joinToString(" ").trim()
            if (code.isEmpty()) return null
            Regex("""\bAS\s+([A-Za-z_][A-Za-z0-9_]*)\s*$""").find(code)?.let { return it.groupValues[1] }
            return code.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
        }
    }
}
