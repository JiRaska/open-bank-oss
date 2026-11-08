// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.accounting

import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate
import java.util.UUID

data class FeeDeliveryReceipt(
    val bookId: UUID,
    val navId: UUID,
    val postingDate: LocalDate,
    val outcome: String,
    val journalId: UUID?,
    val synthetic: Boolean,
)

@ApplicationScoped
class FeeDeliveryReceipts {
    suspend fun bind(fundId: UUID, companyId: UUID) {
        require(fundId != companyId) { "Fund and company must have distinct books" }
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeMutationQuery(
                    "INSERT INTO pension_fund_fee_destinations (fund_id, company_book_id) " +
                        "VALUES (:fund, :company) ON CONFLICT DO NOTHING",
                ).setParameter("fund", fundId).setParameter("company", companyId).executeUpdate().flatMap {
                    session.createNativeQuery<UUID>(
                        "SELECT company_book_id FROM pension_fund_fee_destinations WHERE fund_id = :fund",
                    ).setParameter("fund", fundId).singleResult
                }.invoke { original -> check(original == companyId) { "Fund accounting company binding changed" } }
            }
        }.awaitSuspending()
    }

    suspend fun has(navId: UUID, kind: String): Boolean = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Long>(
                "SELECT count(*) FROM pension_fund_fee_receipts WHERE nav_id = :nav AND book_kind = :kind",
            ).setParameter("nav", navId).setParameter("kind", kind).singleResult
        }
    }.awaitSuspending() == 1L

    suspend fun save(kind: String, receipt: FeeDeliveryReceipt) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeMutationQuery(
                    "INSERT INTO pension_fund_fee_receipts " +
                        "(nav_id, book_kind, book_id, journal_id, posting_date, outcome, synthetic) " +
                        "VALUES (:nav, :kind, :book, :journal, :date, :outcome, :synthetic) ON CONFLICT DO NOTHING",
                ).setParameter("nav", receipt.navId).setParameter("kind", kind).setParameter("book", receipt.bookId)
                    .setParameter("journal", receipt.journalId).setParameter("date", receipt.postingDate)
                    .setParameter(
                        "outcome",
                        receipt.outcome,
                    ).setParameter("synthetic", receipt.synthetic).executeUpdate()
            }
        }.awaitSuspending()
    }
}
