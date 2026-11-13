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
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.idempotency.IdempotencyScope
import com.openbank.libs.idempotency.RequestFingerprints
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
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
    val sourceReferences: Map<String, String?>,
    /** Debit-positive, including every enabled account, even accounts with zero balance. */
    val expectedBalances: Map<String, BigDecimal?>,
)

/** Independent source attestation plus a comparison to the entire own chart, never bank controls. */
@ApplicationScoped
class PensionBookReconciliation(
    private val book: PensionBookIdentity,
    private val json: ObjectMapper,
    private val receipts: PensionFeeReceipts,
    private val clock: Clock,
) {
    suspend fun propose(proposal: PensionReconciliationProposal, actor: String, requestKey: String): UUID {
        checkConflict(book.kind != LedgerBookKind.BANK && book.bookId == proposal.bookId) { "Wrong pension book" }
        val required = if (book.kind == LedgerBookKind.PENSION_FUND) FUND_SOURCES else COMPANY_SOURCES
        requireValid(proposal.sourceReferences.keys == required) { "Reconciliation requires sources $required" }
        requireValid(
            proposal.sourceReferences.values.all { value ->
                !value.isNullOrBlank() &&
                    value.length <= MAX_REFERENCE
            },
        ) {
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
                accountCode.matches(code) && balance != null && balance.scale() <= 2
            },
        ) { "Expected balances require own-book account codes and at most two decimal places" }
        val sources = proposal.sourceReferences.mapValues { (_, value) -> requireNotNull(value) }
        val balances = proposal.expectedBalances.mapValues { (_, value) -> requireNotNull(value) }
        val scopedKey = IdempotencyScope("ledger-pension-reconciliation", actor).storeKey(requestKey)
        val fingerprint = RequestFingerprints.of(json, "POST", RECONCILIATIONS_PATH, proposal)
        receipts.bindBook()
        val id = Ids.newId()
        // The proposal and its replay identity commit together; no reservation survives a rollback.
        return Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeMutationQuery(
                    "INSERT INTO ledger_pension_reconciliations " +
                        "(id, request_key, request_fingerprint, business_date, source_references, " +
                        "expected_balances, proposed_by) " +
                        "VALUES (:id, :key, :fingerprint, :date, :sources, :balances, :actor) " +
                        "ON CONFLICT (request_key) DO NOTHING",
                ).setParameter("id", id).setParameter("date", proposal.businessDate)
                    .setParameter("key", scopedKey).setParameter("fingerprint", fingerprint)
                    .setParameter("sources", json.writeValueAsString(sources))
                    .setParameter("balances", json.writeValueAsString(balances))
                    .setParameter("actor", actor).executeUpdate().flatMap {
                        session.createNativeQuery<Array<Any>>(
                            "SELECT id, request_fingerprint FROM ledger_pension_reconciliations " +
                                "WHERE request_key = :key",
                        ).setParameter("key", scopedKey).singleResult.map { stored ->
                            checkConflict(stored[1].toString() == fingerprint) {
                                "Idempotency-Key was already used for another reconciliation request"
                            }
                            UUID.fromString(stored[0].toString())
                        }
                    }
            }
        }.awaitSuspending()
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
                    checkConflict(proposal[2].toString() != actor) {
                        "Reconciliation requires a different checker"
                    }
                    proposal[APPROVED_BY]?.let { approvedBy ->
                        checkConflict(approvedBy.toString() == actor) {
                            "Reconciliation was approved by another checker"
                        }
                        return@flatMap Uni.createFrom().item(0)
                    }
                    val expected: Map<String, BigDecimal> = json.readValue(
                        proposal[1].toString(),
                        object : TypeReference<Map<String, BigDecimal>>() {},
                    )
                    session.createNativeQuery<Array<Any?>>(BALANCES_SQL).setParameter("d", date).resultList
                        .flatMap { rows ->
                            requireReconciledChart(rows, expected)
                            JournalReadWatermark.readInTransaction(session, date).flatMap { population ->
                                session.createNativeMutationQuery(
                                    "INSERT INTO ledger_tieout_runs " +
                                        "(id, as_of, run_at, started_at, journal_count, journal_fingerprint, " +
                                        "status, accounts_checked, breaks, errors, control_kind) " +
                                        "VALUES (:id, :d, clock_timestamp(), :startedAt, :count, :fingerprint, " +
                                        "'OK', :n, 0, 0, :kind)",
                                ).setParameter("id", id).setParameter("d", date).setParameter("n", rows.size)
                                    .setParameter("startedAt", startedAt).setParameter("count", population.count)
                                    .setParameter("fingerprint", population.fingerprint)
                                    .setParameter("kind", book.kind.name).executeUpdate()
                            }
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

    private fun requireReconciledChart(rows: List<Array<Any?>>, expected: Map<String, BigDecimal>) {
        val prefix = if (book.kind == LedgerBookKind.PENSION_FUND) "PF" else "PS"
        val actual = rows.associate { it[0].toString() to BigDecimal(it[2].toString()) }
        checkConflict(
            rows.isNotEmpty() && rows.all { it[0].toString().startsWith(prefix) && it[1].toString() == "CZK" },
        ) { "Book chart contains unsupported or foreign accounts" }
        checkConflict(
            actual.keys == expected.keys && actual.all { (code, net) -> net.compareTo(expected.getValue(code)) == 0 },
        ) { "Source balances do not reconcile to the entire book" }
    }

    private companion object {
        const val APPROVED_BY = 3
        const val MAX_REFERENCE = 256
        const val MAX_ACCOUNTS = 200
        const val RECONCILIATIONS_PATH = "/api/v1/ledger/pension-accounting/reconciliations"
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
