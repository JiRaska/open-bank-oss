// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.ceu

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.application.port.out.StatutoryReturnRepository
import com.openbank.tax.application.usecase.ReportingEntities
import com.openbank.tax.application.usecase.StatutoryReturnService
import com.openbank.tax.domain.returns.Periodicity
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.ReturnValidationException
import com.openbank.tax.domain.returns.StatutoryReturn
import com.openbank.tax.infrastructure.kafka.CeuRegisterEventConsumer
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger

/** What one tick of the CEÚ job concluded. Returned so tests assert the decision, not a log line. */
enum class CeuTickOutcome { UNCONFIGURED, NOT_A_WORKING_DAY, BEFORE_START, ASSEMBLED, ALREADY_ASSEMBLED, FAILED }

/**
 * The CEÚ daily change overview job (ADR-0367 D7, #15292).
 *
 * Every tick (default every 15 minutes) is idempotent: on a CERTIS working day it assembles the
 * overview for the window ending yesterday if no revision exists yet, and otherwise does nothing.
 * Assembly is the MAKER step only — approval (four-eyes, by a human; the job's actor is a system
 * subject so the approver can never be the assembler), submission to SDAT and ČNB's
 * acknowledgement stay operator actions on the ADR-0336 lifecycle.
 *
 * Two signals make a missed day loud instead of silent:
 *  - workflow liveness `ceu-daily-overview` — a dead scheduler (WorkflowLivenessStale);
 *  - `openbank_ceu_overview_unfiled_past_cutoff` — 1 when today's overview is not SUBMITTED or
 *    ACKNOWLEDGED by the alert cutoff (default 11:00 Prague, an hour before ČNB's 12:00). It is
 *    registered on the first COMPLETED tick, so a cold pod publishes no series rather than a 0
 *    that reads as "filed".
 *
 * A `suspend fun`: a plain @Scheduled method has no Vert.x context and the reactive repository
 * would abort it silently (rules.yaml: scheduled_methods).
 */
@Startup
@ApplicationScoped
class CeuDailyOverviewScheduler(
    private val service: StatutoryReturnService,
    private val returns: StatutoryReturnRepository,
    private val entities: ReportingEntities,
    private val domainMetrics: DomainMetrics,
    private val registry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(CeuDailyOverviewScheduler::class.java)
    private lateinit var liveness: WorkflowLivenessRecorder
    private val unconfigured = AtomicInteger(0)
    private val unfiledPastCutoff = AtomicInteger(0)

    @Volatile
    private var unfiledGaugeRegistered = false

    @PostConstruct
    fun register() {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW, EXPECTED_INTERVAL)
        Gauge.builder("openbank_workflow_unconfigured", unconfigured) { it.get().toDouble() }
            .tag("workflow", WORKFLOW)
            .description("1 while the workflow cannot run because its configuration is not declared")
            .register(registry)
        unconfigured.set(if (start() == null) 1 else 0)
    }

    @Scheduled(
        every = "{openbank.ceu.tick-every:15m}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun scheduledTick() {
        tick()
    }

    @Suppress("TooGenericExceptionCaught") // any failure leaves liveness unrecorded, which is the alarm
    suspend fun tick(): CeuTickOutcome {
        val start = start()
        unconfigured.set(if (start == null) 1 else 0)
        if (start == null) return CeuTickOutcome.UNCONFIGURED

        val now = Instant.now(clock)
        val today = now.atZone(CeuRegisterEventConsumer.PRAGUE).toLocalDate()
        if (!ReportingPeriod.WORKING_DAYS.isBusinessDay(today)) {
            publishUnfiled(0)
            liveness.recordSuccess()
            return CeuTickOutcome.NOT_A_WORKING_DAY
        }
        val period = ReportingPeriod(Periodicity.WORKING_DAY, today.minusDays(1))
        if (period.endDate.isBefore(start)) {
            publishUnfiled(0)
            liveness.recordSuccess()
            return CeuTickOutcome.BEFORE_START
        }

        return try {
            val existing = returns.latestRevision(CATALOGUE, CODE, entities.companyId, period)
            val outcome = if (existing == null) {
                val assembled = service.assemble(CATALOGUE, CODE, entities.companyId, period.label, ACTOR)
                log.infof(
                    "[ceu] assembled %s %s r%d: %s changes, %s synthetic accounts dropped",
                    CODE,
                    period.label,
                    assembled.revision,
                    assembled.datapoints["changes_total"]?.toPlainString(),
                    assembled.datapoints["synthetic_accounts_dropped"]?.toPlainString(),
                )
                publishUnfiled(unfiledFlag(assembled, now))
                CeuTickOutcome.ASSEMBLED
            } else {
                publishUnfiled(unfiledFlag(existing, now))
                CeuTickOutcome.ALREADY_ASSEMBLED
            }
            liveness.recordSuccess()
            outcome
        } catch (e: ReturnValidationException) {
            // Rule ids and a count only: finding messages name account numbers, which do not
            // belong in logs. The full findings are reproduced by re-assembling through the API.
            log.errorf(
                "[ceu] %s %s failed validation, nothing stored: %d finding(s), rules %s",
                CODE,
                period.label,
                e.findings.size,
                e.findings.map { it.ruleId }.distinct(),
            )
            publishUnfiled(unfiledFlag(null, now))
            CeuTickOutcome.FAILED
        } catch (e: ReturnDataUnavailableException) {
            log.errorf("[ceu] %s %s cannot be assembled: %s", CODE, period.label, e.message)
            publishUnfiled(unfiledFlag(null, now))
            CeuTickOutcome.FAILED
        } catch (e: RuntimeException) {
            log.errorf(e, "[ceu] %s %s tick failed", CODE, period.label)
            CeuTickOutcome.FAILED
        }
    }

    private fun unfiledFlag(latest: StatutoryReturn?, now: Instant): Int {
        val pastCutoff = !now.atZone(CeuRegisterEventConsumer.PRAGUE).toLocalTime().isBefore(ALERT_CUTOFF)
        return if (pastCutoff && latest?.status?.filed != true) 1 else 0
    }

    private fun publishUnfiled(value: Int) {
        unfiledPastCutoff.set(value)
        if (!unfiledGaugeRegistered) {
            Gauge.builder("openbank_ceu_overview_unfiled_past_cutoff", unfiledPastCutoff) { it.get().toDouble() }
                .description("1 when today's CEÚ change overview is not filed with ČNB by the alert cutoff (ADR-0367)")
                .register(registry)
            unfiledGaugeRegistered = true
        }
    }

    private fun start() = entities.catalogueStarts[CATALOGUE]

    companion object {
        const val WORKFLOW = "ceu-daily-overview"
        const val CATALOGUE = CeuReturnDataAdapter.CATALOGUE_ID
        const val CODE = CeuReturnDataAdapter.RETURN_CODE

        /** The maker identity of a job-assembled overview — never a person, so any human may approve it. */
        const val ACTOR = "system:ceu-daily-overview"

        /** An hour before ČNB's 12:00 deadline, so the alert leaves time to act. */
        val ALERT_CUTOFF: LocalTime = LocalTime.of(11, 0)
        private val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(15)
    }
}
