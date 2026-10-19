// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.close

import com.openbank.libs.close.CloseParticipationReporter
import com.openbank.libs.close.CloseParticipationSink
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.port.out.DayCompletionRepository
import com.openbank.treasury.domain.model.DayStep
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Reports treasury's day-close participation to the ledger (ADR-0342). Every tick re-offers the last
 * [lookbackDays] completed accounting days; a day whose due steps have not all finished is not
 * reported, so the ledger waits for it. The reporter never re-sends an identical delivered report.
 *
 * A `suspend fun`: a plain `@Scheduled` method has no Vert.x context, and a `runBlocking` around
 * reactive Panache throws HR000068 (check-no-runblocking-in-scheduled).
 */
@ApplicationScoped
class TreasuryCloseParticipationScheduler(
    private val dayCompletion: DayCompletionRepository,
    sink: CloseParticipationSink,
    meterRegistry: MeterRegistry,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.treasury.close.enabled", defaultValue = "true")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.treasury.close.due-steps", defaultValue = "ACCRUALS")
    dueStepsConfig: String,
    @ConfigProperty(name = "openbank.treasury.close.lookback-days", defaultValue = "3")
    private val lookbackDays: Long,
) {
    private val log = Logger.getLogger(TreasuryCloseParticipationScheduler::class.java)
    private val reporter = CloseParticipationReporter(TreasuryCloseParticipation.PARTICIPANT, sink, meterRegistry)
    private val dueSteps: Set<DayStep> = TreasuryCloseParticipation.dueSteps(dueStepsConfig)
    private var liveness: WorkflowLivenessRecorder? = null

    init {
        require(lookbackDays in 1..MAX_LOOKBACK) { "openbank.treasury.close.lookback-days must be 1..$MAX_LOOKBACK" }
    }

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofHours(1))
    }

    @Scheduled(
        cron = "{openbank.treasury.close.report-cron:0 40 * * * ?}",
        identity = "treasury-close-participation",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun reportRecentDays() {
        if (!enabled) return
        runCatching { reportDays(AccountingClock.bank(clock).today()) }
            .onSuccess { r ->
                if (r.delivered == r.attempted) {
                    liveness?.recordSuccess()
                } else {
                    log.warnf(
                        "treasury day-close: %d of %d report(s) NOT accepted by the ledger",
                        r.attempted - r.delivered,
                        r.attempted,
                    )
                }
            }
            .onFailure { log.error("treasury day-close participation tick failed", it) }
    }

    data class TickResult(val attempted: Int, val delivered: Int)

    /** Report every completed day in `[today - lookbackDays, today - 1]`. Visible for the IT. */
    suspend fun reportDays(today: LocalDate): TickResult {
        val from = today.minusDays(lookbackDays)
        val to = today.minusDays(1)
        val records = dayCompletion.between(from, to)
        var attempted = 0
        var delivered = 0
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.forEach { day ->
            val report = TreasuryCloseParticipation.report(day, dueSteps, records)
            if (report == null) {
                log.infof("treasury day-close for %s not reported: a due step has not finished", day)
            } else {
                attempted++
                if (reporter.report(report).awaitSuspending()) delivered++
            }
        }
        return TickResult(attempted, delivered)
    }

    private companion object {
        const val WORKFLOW_NAME = "treasury-close-participation"
        const val MAX_LOOKBACK = 31L
    }
}
