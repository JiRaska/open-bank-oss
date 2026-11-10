// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.usecase.LedgerBookKind
import com.openbank.ledger.application.usecase.PensionBookIdentity
import com.openbank.ledger.domain.model.checkConflict
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.LocalDate
import java.util.UUID

data class PensionFeeReceipt(val postingDate: LocalDate, val outcome: String, val journalId: UUID?)

/** Reserve immutable source identity before remote retries can cause any financial mutation. */
@ApplicationScoped
class PensionFeeReceipts(private val book: PensionBookIdentity) {
    /** Bounded, oldest-first recovery also covers books stalled beyond a wall-clock lookback. */
    suspend fun pendingDays(through: LocalDate): List<Pair<LocalDate, String>> = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Array<Any>>(
                "SELECT business_date, status FROM ledger_accounting_day " +
                    "WHERE status IN ('OPEN', 'CUTOFF') AND business_date <= :through " +
                    "ORDER BY business_date LIMIT :batch",
            ).setParameter("through", through).setParameter("batch", PENDING_BATCH_SIZE).resultList
        }
    }.awaitSuspending().map { LocalDate.parse(it[0].toString()) to it[1].toString() }

    suspend fun requireOriginal(navId: UUID, fundId: UUID, synthetic: Boolean) {
        val count = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Long>(
                    "SELECT count(*) FROM ledger_pension_fee_receipts WHERE nav_id = :nav " +
                        "AND fund_id = :fund AND synthetic = :synthetic AND outcome IN ('POSTED', 'NO_POSTING')",
                ).setParameter(
                    "nav",
                    navId,
                ).setParameter("fund", fundId).setParameter("synthetic", synthetic).singleResult
            }
        }.awaitSuspending()
        checkConflict(count == 1L) { "Superseded NAV accounting has not completed in this book" }
    }
    suspend fun bindBook() {
        Panache.withTransaction {
            Panache.getSession().flatMap { session -> bindBook(session) }
        }.awaitSuspending()
    }

    private fun bindBook(session: Mutiny.Session): Uni<Unit> {
        checkConflict(book.kind != LedgerBookKind.BANK) { "A pension operation requires a pension book" }
        return session.createNativeMutationQuery(
            "INSERT INTO ledger_book_identity (book_kind, book_id) VALUES (:kind, :id) ON CONFLICT DO NOTHING",
        ).setParameter("kind", book.kind.name).setParameter("id", book.bookId).executeUpdate().flatMap {
            session.createNativeQuery<Long>(
                "SELECT count(*) FROM ledger_book_identity WHERE book_kind = :kind AND book_id = :id",
            ).setParameter("kind", book.kind.name).setParameter("id", book.bookId).singleResult
        }.map { count ->
            checkConflict(count == 1L) { "Ledger database belongs to another book" }
        }
    }

    suspend fun reserve(
        navId: UUID,
        fundId: UUID,
        fingerprint: String,
        postingDate: LocalDate,
        synthetic: Boolean,
    ): PensionFeeReceipt = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            bindBook(session)
                .flatMap {
                    session.createNativeMutationQuery(
                        "INSERT INTO ledger_pension_fee_receipts " +
                            "(nav_id, fund_id, fingerprint, posting_date, outcome, synthetic) " +
                            "VALUES (:nav, :fund, :hash, :date, 'PENDING', :synthetic) ON CONFLICT DO NOTHING",
                    ).setParameter("nav", navId).setParameter("fund", fundId)
                        .setParameter("hash", fingerprint).setParameter("date", postingDate)
                        .setParameter("synthetic", synthetic).executeUpdate()
                }.flatMap {
                    session.createNativeQuery<Array<Any?>>(
                        "SELECT fingerprint, posting_date, outcome, journal_id " +
                            "FROM ledger_pension_fee_receipts WHERE nav_id = :nav",
                    ).setParameter("nav", navId).singleResult
                }.map { row ->
                    checkConflict(row[0].toString() == fingerprint) { "NAV accounting payload changed on replay" }
                    PensionFeeReceipt(
                        LocalDate.parse(row[1].toString()),
                        row[2].toString(),
                        row[JOURNAL_ID]?.let { UUID.fromString(it.toString()) },
                    )
                }
        }
    }.awaitSuspending()

    private companion object {
        const val JOURNAL_ID = 3
        const val PENDING_BATCH_SIZE = 31
    }

    suspend fun complete(navId: UUID, journalId: UUID?) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeMutationQuery(
                    "UPDATE ledger_pension_fee_receipts SET outcome = :outcome, journal_id = :journal, " +
                        "completed_at = now() WHERE nav_id = :nav AND outcome = 'PENDING'",
                ).setParameter("outcome", if (journalId == null) "NO_POSTING" else "POSTED")
                    .setParameter("journal", journalId).setParameter("nav", navId).executeUpdate()
            }
        }.awaitSuspending()
    }
}
