// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.ledger.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** One SQL snapshot of the bank and its books, in native currency and excluding synthetic activity. */
@Path("/api/v1/journals/branch-books")
@Produces(MediaType.APPLICATION_JSON)
class BranchBookResource(private val pool: Pool) {
    @GET
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read")
    suspend fun snapshot(@QueryParam("bankId") bankId: UUID?, @QueryParam("asOf") asOf: String?): BranchLedgerSnapshot {
        val bank = requireNotNull(bankId) { "bankId is required" }
        val date = try {
            LocalDate.parse(requireNotNull(asOf) { "asOf is required" })
        } catch (
            e: java.time.format.DateTimeParseException,
        ) {
            throw IllegalArgumentException("asOf must be an ISO date", e)
        }
        val rows = pool.preparedQuery(SNAPSHOT).execute(Tuple.of(bank, date)).awaitSuspending()
        val first = rows.firstOrNull() ?: throw IllegalArgumentException("bank has no registered branch book")
        check(first.getBoolean("single_bank")) { "Unattributed bank journals cannot be assigned across multiple banks" }
        return BranchLedgerSnapshot(
            bank,
            date,
            rows.filter { it.getString("currency") != null }.map {
                BranchLedgerLine(
                    it.getUUID("branch_book_id"),
                    it.getString("currency").trim(),
                    it.getBigDecimal("debit"),
                    it.getBigDecimal("credit"),
                    it.getBigDecimal("deposits"),
                    it.getString("base_currency").trim(),
                    it.getBigDecimal("clearing"),
                )
            },
            first.getBoolean("clearing_balanced"),
        )
    }

    private companion object {
        val SNAPSHOT = """
            WITH bank AS (
                SELECT DISTINCT bank_id FROM ledger_branch_books WHERE bank_id = $1
            ), totals AS (
                SELECT e.branch_book_id, l.currency_code AS currency, l.base_currency,
                    sum(CASE WHEN l.side='D' THEN l.base_amount ELSE 0 END) AS debit,
                    sum(CASE WHEN l.side='C' THEN l.base_amount ELSE 0 END) AS credit,
                    sum(CASE WHEN g.code IN ('2100','2101','2102','2103') THEN
                        CASE WHEN l.side='C' THEN l.amount ELSE -l.amount END ELSE 0 END) AS deposits,
                    sum(CASE WHEN g.code IN ('1290','1291','1292','1293') THEN
                        CASE WHEN l.side='D' THEN l.amount ELSE -l.amount END ELSE 0 END) AS clearing
                FROM journal_entries e JOIN journal_lines l ON l.journal_id=e.id
                JOIN gl_accounts g ON g.id=l.gl_account_id
                LEFT JOIN ledger_branch_books b ON b.establishment_id=e.branch_book_id
                WHERE e.status IN ('POSTED','REVERSED') AND e.entry_date <= $2 AND NOT e.synthetic
                    AND (e.branch_book_id IS NULL OR b.bank_id=$1)
                GROUP BY e.branch_book_id,l.currency_code,l.base_currency
            )
            SELECT (SELECT count(DISTINCT bank_id)=1 FROM ledger_branch_books) AS single_bank,
                NOT EXISTS (
                    SELECT p.operation_key FROM ledger_branch_payments p
                    JOIN journal_entries e ON e.id=p.first_journal_id OR e.id=p.second_journal_id
                    JOIN journal_lines l ON l.journal_id=e.id JOIN gl_accounts g ON g.id=l.gl_account_id
                    WHERE p.bank_id=$1 AND e.entry_date <= $2 AND NOT e.synthetic
                        AND g.code IN ('1290','1291','1292','1293')
                    GROUP BY p.operation_key,l.currency_code
                    HAVING sum(CASE WHEN l.side='D' THEN l.amount ELSE -l.amount END) <> 0
                ) AS clearing_balanced, totals.*
            FROM bank LEFT JOIN totals ON true ORDER BY branch_book_id NULLS FIRST,currency
        """.trimIndent()
    }
}

data class BranchLedgerSnapshot(
    val bankId: UUID,
    val asOf: LocalDate,
    val lines: List<BranchLedgerLine>,
    val clearingBalanced: Boolean = true,
)
data class BranchLedgerLine(
    val branchBookId: UUID?,
    val currency: String,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val deposits: BigDecimal,
    val baseCurrency: String = currency,
    val clearing: BigDecimal = BigDecimal.ZERO,
)
