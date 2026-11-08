// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.persistence.repository

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.application.usecase.LedgerBookKind
import com.openbank.ledger.application.usecase.PensionBookIdentity
import com.openbank.ledger.domain.model.checkConflict
import com.openbank.ledger.domain.model.requireValid
import com.openbank.libs.domain.calendar.AccountingClock
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class PensionReconciliationProposal(
    val bookId: UUID,
    val businessDate: LocalDate,
    val sourceReferences: Map<String, String>,
    /** Debit-positive, including every enabled account, even accounts with zero balance. */
    val expectedBalances: Map<String, BigDecimal>,
)

/** Independent source attestation plus a comparison to the entire own chart, never bank controls. */
@ApplicationScoped
class PensionBookReconciliation(
    private val book: PensionBookIdentity,
    private val json: ObjectMapper,
    private val receipts: PensionFeeReceipts,
    private val clock: Clock,
) {
    suspend fun propose(proposal: PensionReconciliationProposal, actor: String): UUID {
        checkConflict(book.kind != LedgerBookKind.BANK && book.bookId == proposal.bookId) { "Wrong pension book" }
        val required = if (book.kind == LedgerBookKind.PENSION_FUND) FUND_SOURCES else COMPANY_SOURCES
        requireValid(proposal.sourceReferences.keys == required) { "Reconciliation requires sources $required" }
        requireValid(proposal.sourceReferences.values.all { value: String? -> !value.isNullOrBlank() && value.length <= MAX_REFERENCE }) {
            "Every source requires an opaque evidence reference"
        }
        requireValid(proposal.expectedBalances.isNotEmpty() && proposal.expectedBalances.size <= MAX_ACCOUNTS) {
            "A complete, bounded source balance is required"
        }
        requireValid(!AccountingClock.bank(clock).isFuture(proposal.businessDate)) {
            "Reconciliation business date must not be in the future"
        }
        val accountCode = if (book.kind == LedgerBookKind.PENSION_FUND) FUND_ACCOUNT_CODE else COMPANY_ACCOUNT_CODE
        requireValid(
            proposal.expectedBalances.all { (code, balance) ->
                val nullableBalance: BigDecimal? = balance
                accountCode.matches(code) && nullableBalance != null && nullableBalance.scale() <= 2
            },
        ) { "Expected balances require own-book account codes and at most two decimal places" }
        receipts.bindBook()
        val id = UUID.randomUUID()
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeMutationQuery(
                    "INSERT INTO ledger_pension_reconciliations " +
                        "(id, business_date, source_references, expected_balances, proposed_by) " +
                        "VALUES (:id, :date, :sources, :balances, :actor)",
                ).setParameter("id", id).setParameter("date", proposal.businessDate)
                    .setParameter("sources", json.writeValueAsString(proposal.sourceReferences))
                    .setParameter("balances", json.writeValueAsString(proposal.expectedBalances))
                    .setParameter("actor", actor).executeUpdate()
            }
        }.awaitSuspending()
        return id
    }

    suspend fun approve(id: UUID, actor: String) {
        val startedAt = Instant.now(clock)
        receipts.bindBook()
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                JournalReadWatermark.lockCapture(session).flatMap {
                    session.createNativeQuery<Array<Any?>>(
                        "SELECT business_date, expected_balances, proposed_by, approved_by " +
                            "FROM ledger_pension_reconciliations WHERE id = :id FOR UPDATE",
                    ).setParameter("id", id).resultList.map { rows ->
                        rows.firstOrNull() ?: throw jakarta.ws.rs.NotFoundException("Reconciliation not found")
                    }
                }.flatMap { proposal ->
                    val date = LocalDate.parse(proposal[0].toString())
                    checkConflict(proposal[2].toString() != actor && proposal[APPROVED_BY] == null) {
                        "Reconciliation requires a different checker and may be approved once"
                    }
                    val expected: Map<String, BigDecimal> = json.readValue(
                        proposal[1].toString(),
                        object : TypeReference<Map<String, BigDecimal>>() {},
                    )
                    session.createNativeQuery<Array<Any?>>(BALANCES_SQL).setParameter("d", date).resultList
                        .flatMap { rows ->
                            val prefix = if (book.kind == LedgerBookKind.PENSION_FUND) "PF" else "PS"
                            val actual = rows.associate { it[0].toString() to BigDecimal(it[2].toString()) }
                            checkConflict(
                                rows.isNotEmpty() &&
                                    rows.all {
                                        it[0].toString().startsWith(prefix) && it[1].toString() == "CZK"
                                    },
                            ) { "Book chart contains unsupported or foreign accounts" }
                            checkConflict(
                                actual.keys == expected.keys &&
                                    actual.all { (code, net) ->
                                        net.compareTo(expected.getValue(code)) == 0
                                    },
                            ) { "Source balances do not reconcile to the entire book" }
                            session.createNativeMutationQuery(
                                "INSERT INTO ledger_tieout_runs " +
                                    "(id, as_of, run_at, started_at, journal_count, status, accounts_checked, " +
                                    "breaks, errors, control_kind) " +
                                    "SELECT :id, :d, clock_timestamp(), :startedAt, count(*), 'OK', :n, 0, 0, :kind " +
                                    "FROM journal_entries WHERE entry_date <= :d",
                            ).setParameter("id", id).setParameter("d", date).setParameter("n", rows.size)
                                .setParameter("startedAt", startedAt)
                                .setParameter("kind", book.kind.name).executeUpdate()
                        }.flatMap {
                            session.createNativeMutationQuery(
                                "UPDATE ledger_pension_reconciliations SET approved_by = :actor, approved_at = now() " +
                                    "WHERE id = :id",
                            ).setParameter("actor", actor).setParameter("id", id).executeUpdate()
                        }
                }
            }
        }.awaitSuspending()
    }

    private companion object {
        const val APPROVED_BY = 3
        const val MAX_REFERENCE = 256
        const val MAX_ACCOUNTS = 200
        val FUND_ACCOUNT_CODE = Regex("PF[0-9]{4}")
        val COMPANY_ACCOUNT_CODE = Regex("PS[0-9]{4}")
        val FUND_SOURCES = setOf("cash", "units", "custody", "nav", "fees", "settlements")
        val COMPANY_SOURCES = setOf("bank", "receivables", "portfolio", "expenses", "tax")
        const val BALANCES_SQL = "SELECT g.code, g.currency_code, COALESCE(b.net, 0) FROM gl_accounts g " +
            "LEFT JOIN (SELECT l.gl_account_id, sum(CASE WHEN l.side = 'D' THEN l.base_amount " +
            "ELSE -l.base_amount END) net FROM journal_lines l JOIN journal_entries e ON e.id = l.journal_id " +
            "WHERE e.entry_date <= :d AND e.status IN ('POSTED', 'REVERSED') AND NOT e.synthetic " +
            "GROUP BY l.gl_account_id) b ON b.gl_account_id = g.id " +
            "WHERE g.is_leaf AND (g.is_enabled OR COALESCE(b.net, 0) <> 0) ORDER BY g.code"
    }
}
