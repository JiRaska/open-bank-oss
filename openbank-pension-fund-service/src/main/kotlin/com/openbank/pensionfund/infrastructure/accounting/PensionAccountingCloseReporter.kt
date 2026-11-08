// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.accounting

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.pensionfund.application.port.PensionFundStore
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Only NAV/fee completion is owned here. Cash, custody and settlements remain separate producers. */
@ApplicationScoped
class PensionAccountingCloseReporter(
    private val config: PensionAccountingConfig,
    private val clients: PensionBookClients,
    private val evidence: FundAccountingEvidence,
    private val store: PensionFundStore,
    private val clock: java.time.Clock,
    private val receipts: FeeDeliveryReceipts,
) {
    @Scheduled(
        every = "\${openbank.pension-fund.accounting.close-poll-interval:5m}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "pension-accounting-close",
    )
    suspend fun report() {
        if (!config.deliveryEnabled()) return
        val funds = store.funds().map { it.id }.toSet()
        val declared = config.funds().keys.map { UUID.fromString(it) }.toSet()
        check(funds.isNotEmpty() && declared == funds) { "Every fund needs its own accounting destination" }
        val company = config.companyBookId().orElseThrow { IllegalStateException("Company book id is missing") }
        for (fund in funds) receipts.bind(fund, company)
        reportBook(company, "PENSION_COMPANY", config.companyUrl().orElseThrow(), funds)
        for (fund in funds) reportBook(fund, "PENSION_FUND", config.funds().getValue(fund.toString()), setOf(fund))
    }

    private suspend fun reportBook(bookId: UUID, kind: String, url: URI, funds: Set<UUID>) {
        clients.open(url).use { client ->
            val identity = client.identity()
            check(identity.bookId == bookId && identity.kind == kind) { "Close destination belongs to another book" }
            val today = AccountingClock.bank(clock).today()
            val days = client.days(today.minusDays(LOOKBACK_DAYS), today)
            for (day in days.filter { it.status in setOf("OPEN", "CUTOFF") }) {
                val source = funds.map { evidence.day(it, day.businessDate, kind) }
                val blockers = source.flatMap { it.blockers }.distinct().sorted()
                val ready = blockers.isEmpty()
                client.report(
                    day.businessDate,
                    PensionCloseReport(
                        if (ready) "RAN" else "REFUSED",
                        source.sumOf { it.journalsPosted },
                        source.sumOf { it.sourceNavs },
                        if (ready) null else blockers.joinToString(","),
                        if (!ready) {
                            emptySet()
                        } else if (kind == "PENSION_FUND") {
                            setOf("nav", "management-fees")
                        } else {
                            setOf("management-fees")
                        },
                        sourceRunId = sourceRunId(source, ready, blockers),
                    ),
                )
                if (!ready) {
                    Logger.getLogger(javaClass).warnf(
                        "Pension close %s %s refused: %s",
                        kind,
                        day.businessDate,
                        blockers,
                    )
                }
            }
        }
    }

    private fun sourceRunId(source: List<FundAccountingDayEvidence>, ready: Boolean, blockers: List<String>): String {
        val identity = (source.map { requireNotNull(it.sourceRunId) }.sorted() + ready.toString() + blockers)
            .joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val LOOKBACK_DAYS = 7L
    }
}
