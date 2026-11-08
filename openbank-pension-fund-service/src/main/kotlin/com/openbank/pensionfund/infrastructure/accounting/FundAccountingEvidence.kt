// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.accounting

import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

data class FundAccountingDayEvidence(
    val fundId: UUID,
    val businessDate: LocalDate,
    val bookKind: String,
    val sourceNavs: Int,
    val journalsPosted: Int,
    val blockers: List<String>,
    val sourceRunId: String? = null,
) {
    val complete: Boolean get() = blockers.isEmpty()
}

@ApplicationScoped
class FundAccountingEvidence {
    private companion object {
        const val NAV_ID = 0
        const val NAV_STATUS = 1
        const val VALUATION_DATE = 2
        const val SYNTHETIC = 3
        const val RECEIPT_OUTCOME = 4
        const val POSTING_DATE = 5
        const val JOURNAL_ID = 6
        const val RECEIVED_AT = 7
    }

    suspend fun day(fundId: UUID, date: LocalDate, kind: String): FundAccountingDayEvidence {
        require(kind in setOf("PENSION_FUND", "PENSION_COMPANY")) { "Unknown pension book kind" }
        val rows = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Array<Any?>>(
                    "SELECT n.id, n.status, n.valuation_date, i.synthetic, " +
                        "r.outcome, r.posting_date, r.journal_id, r.received_at FROM fund_navs n " +
                        "LEFT JOIN pension_fund_fee_intents i ON i.nav_id = n.id " +
                        "LEFT JOIN pension_fund_fee_destinations d ON d.fund_id = n.fund_id " +
                        "LEFT JOIN pension_fund_fee_receipts r ON r.nav_id = n.id AND r.book_kind = :kind " +
                        "AND r.book_id = CASE WHEN :kind = 'PENSION_FUND' THEN n.fund_id " +
                        "ELSE d.company_book_id END " +
                        "WHERE n.fund_id = :fund AND n.valuation_date <= :d " +
                        "AND n.status IN ('CALCULATED', 'PUBLISHED', 'SUPERSEDED') " +
                        "ORDER BY n.valuation_date, n.id",
                ).setParameter("fund", fundId).setParameter("d", date).setParameter("kind", kind).resultList
            }
        }.awaitSuspending()
        val approved = rows.filter { it[NAV_STATUS] != "CALCULATED" }
        val daily = approved.filter { LocalDate.parse(it[VALUATION_DATE].toString()) == date }
        val blockers = buildList {
            if (daily.isEmpty()) add("NO_APPROVED_NAV_OR_DUE_CALENDAR")
            if (rows.any { it[NAV_STATUS] == "CALCULATED" }) add("PENDING_NAV_APPROVAL")
            if (approved.any { it[SYNTHETIC] == null }) add("HISTORICAL_NAV_HAS_NO_ACCOUNTING_INTENT")
            if (daily.any { it[SYNTHETIC] == true }) add("SYNTHETIC_NAV_CANNOT_CERTIFY_REAL_BOOK")
            if (approved.any { it[SYNTHETIC] != true && it[RECEIPT_OUTCOME] == null }) {
                add("ACCOUNTING_DELIVERY_INCOMPLETE")
            }
            if (daily.any { it[POSTING_DATE] != null && LocalDate.parse(it[POSTING_DATE].toString()) > date }) {
                add("LATE_ACCOUNTING_POSTING")
            }
        }
        return FundAccountingDayEvidence(
            fundId,
            date,
            kind,
            daily.size,
            daily.count { it[RECEIPT_OUTCOME] == "POSTED" },
            blockers,
            sourceRunId(fundId, date, kind, rows),
        )
    }

    private fun sourceRunId(fundId: UUID, date: LocalDate, kind: String, rows: List<Array<Any?>>): String {
        val source = buildString {
            append("$fundId|$date|$kind\n")
            for (row in rows.filter { it[SYNTHETIC] != true }) {
                append(
                    listOf(NAV_ID, NAV_STATUS, VALUATION_DATE, JOURNAL_ID, RECEIPT_OUTCOME, POSTING_DATE, RECEIVED_AT)
                        .joinToString("|") { row[it]?.toString() ?: "" },
                )
                append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
