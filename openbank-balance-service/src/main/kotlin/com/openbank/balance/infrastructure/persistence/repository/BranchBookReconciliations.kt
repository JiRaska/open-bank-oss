// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.balance.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.domain.reconciliation.BranchBookReconciliation
import com.openbank.balance.domain.reconciliation.BranchBookReport
import com.openbank.balance.domain.reconciliation.ProjectedBookTotal
import com.openbank.balance.infrastructure.client.LedgerBranchBooksClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class BranchBookReconciliations(
    private val pool: Pool,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val metrics: com.openbank.libs.observability.DomainMetrics,
    @RestClient private val ledger: LedgerBranchBooksClient,
) {
    suspend fun run(bank: UUID, date: LocalDate, operationKey: String? = null): BranchBookReport {
        operationKey?.let {
            require(it.isNotBlank() && it.length <= MAX_OPERATION_KEY_LENGTH) { "invalid Idempotency-Key" }
            operation(it, bank, date)?.let { report -> return report }
        }
        val snapshot = ledger.snapshot(bank, date.toString()).awaitSuspending()
        check(snapshot.bankId == bank && snapshot.asOf == date) { "Ledger returned a different reconciliation scope" }
        val rows = pool.preparedQuery(TOTALS).execute(Tuple.of(bank, date)).awaitSuspending().map {
            ProjectedBookTotal(
                it.getUUID("bank_id"),
                it.getUUID("branch_book_id"),
                it.getString("currency").trim(),
                it.getBigDecimal("booked"),
                it.getLong("unknown_accounts"),
            )
        }
        val report = BranchBookReconciliation.reconcile(snapshot, rows, clock.instant())
        val inserted = pool.preparedQuery(
            "INSERT INTO branch_book_reconciliations(bank_id,as_of,report,operation_key) VALUES ($1,$2,$3,$4) " +
                "ON CONFLICT(operation_key) DO NOTHING",
        ).execute(Tuple.of(bank, date, JsonObject(mapper.writeValueAsString(report)), operationKey)).awaitSuspending()
        if (inserted.rowCount() == 0) {
            return checkNotNull(operation(checkNotNull(operationKey), bank, date))
        }
        report.books.groupBy { it.currency }.forEach { (currency, rows) ->
            // Opposite branch errors must never cancel in a bank-level gauge.
            metrics.recordReconciliationDrift(
                "balance_branch_books",
                currency,
                rows.maxOf { it.difference.abs() },
            )
        }
        return report
    }

    private suspend fun operation(key: String, bank: UUID, date: LocalDate): BranchBookReport? {
        val row = pool.preparedQuery(
            "SELECT bank_id,as_of,report FROM branch_book_reconciliations WHERE operation_key=$1",
        ).execute(Tuple.of(key)).awaitSuspending().firstOrNull() ?: return null
        if (row.getUUID("bank_id") != bank || row.getLocalDate("as_of") != date) {
            throw com.openbank.libs.domain.error.ResourceConflictException(
                "reconciliation key reused for a different scope",
            )
        }
        return mapper.readValue(row.getJsonObject("report").encode(), BranchBookReport::class.java)
    }

    suspend fun latest(bank: UUID): BranchBookReport? = pool.preparedQuery(
        "SELECT report FROM branch_book_reconciliations WHERE bank_id=$1 ORDER BY id DESC LIMIT 1",
    ).execute(Tuple.of(bank)).awaitSuspending().firstOrNull()?.let {
        mapper.readValue(it.getJsonObject("report").encode(), BranchBookReport::class.java)
    }

    suspend fun banks(): List<UUID> = pool.query(
        "SELECT DISTINCT bank_id FROM account_booking_context WHERE bank_id IS NOT NULL AND NOT synthetic",
    ).execute().awaitSuspending().map { it.getUUID("bank_id") }

    private companion object {
        const val MAX_OPERATION_KEY_LENGTH = 255
        val TOTALS = """
            SELECT c.bank_id, CASE WHEN c.mode='BRANCH' THEN c.establishment_id END AS branch_book_id,
                b.currency, sum(b.booked_amount-coalesce(f.delta,0)) AS booked,
                count(*) FILTER (WHERE c.bank_id IS NULL) AS unknown_accounts
            FROM balances b LEFT JOIN account_booking_context c ON c.account_id=b.account_id
            LEFT JOIN LATERAL (
                SELECT sum(p.delta) AS delta FROM ledger_projection_event p
                WHERE p.account_id=b.account_id AND p.currency=b.currency AND p.entry_date>$2
            ) f ON true
            WHERE (c.bank_id=$1 OR c.bank_id IS NULL) AND NOT coalesce(c.synthetic,false)
            GROUP BY c.bank_id,CASE WHEN c.mode='BRANCH' THEN c.establishment_id END,b.currency
        """.trimIndent()
    }
}
