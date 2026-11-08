// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.accounting

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.pensionfund.application.port.PensionFundStore
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import kotlinx.coroutines.CancellationException
import org.jboss.logging.Logger
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/** Only NAV/fee completion is owned here. Cash, custody and settlements remain separate producers. */
@ApplicationScoped
class PensionAccountingCloseReporter(
    private val config: PensionAccountingConfig,
    private val clients: PensionBookClients,
    private val evidence: FundAccountingEvidence,
    private val store: PensionFundStore,
    private val receipts: FeeDeliveryReceipts,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(javaClass)
    private var liveness: WorkflowLivenessRecorder? = null

    fun register(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (config.deliveryEnabled()) {
            liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW, config.closePollInterval())
        }
    }

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
        val companyUrl = config.companyUrl().orElseThrow { IllegalStateException("Company destination is missing") }
        for (fund in funds) receipts.bind(fund, company)
        val destinations = listOf(Destination(company, "PENSION_COMPANY", companyUrl, funds)) + funds.map { fund ->
            Destination(fund, "PENSION_FUND", config.funds().getValue(fund.toString()), setOf(fund))
        }
        var firstFailure: Exception? = null
        for (destination in destinations) {
            try {
                reportBook(destination.bookId, destination.kind, destination.url, destination.funds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                // Isolate any adapter failure; the first error still fails this scheduler invocation.
                if (firstFailure == null) firstFailure = failure
                log.errorf(failure, "Pension close destination %s %s failed", destination.kind, destination.bookId)
            }
        }
        firstFailure?.let { throw it }
        // Accepted REFUSED reports are successful delivery; a partial destination failure is not.
        liveness?.recordSuccess()
    }

    private suspend fun reportBook(bookId: UUID, kind: String, url: URI, funds: Set<UUID>) {
        clients.open(url).use { client ->
            val identity = client.identity()
            check(identity.bookId == bookId && identity.kind == kind) { "Close destination belongs to another book" }
            val today = AccountingClock.bank(clock).today()
            val monthStart = today.withDayOfMonth(1)
            val pending = client.pendingDays()
            val previous = client.days(monthStart.minusMonths(1), monthStart.minusDays(1))
            val current = client.days(monthStart, today)
            val days = (pending + previous + current).distinctBy { it.businessDate }.sortedBy { it.businessDate }
            for (day in days) {
                val source = funds.map { evidence.day(it, day.businessDate, kind) }
                val blockers = source.flatMap { it.blockers }.distinct().sorted()
                val ready = blockers.isEmpty()
                client.report(
                    day.businessDate,
                    PensionCloseReport(
                        if (ready) "RAN" else "REFUSED",
                        if (ready) source.sumOf { it.journalsPosted } else 0,
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
                    log.warnf(
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

    private data class Destination(val bookId: UUID, val kind: String, val url: URI, val funds: Set<UUID>)

    companion object {
        const val WORKFLOW = "pension-accounting-close"
    }
}
