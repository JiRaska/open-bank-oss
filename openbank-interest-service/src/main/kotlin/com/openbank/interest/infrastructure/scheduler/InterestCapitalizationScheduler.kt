// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.scheduler

import com.openbank.interest.application.port.`in`.CapitalizeInterestUseCase
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Drives the monthly interest capitalization. One tick per month (cron in
 * `openbank.interest.capitalization-cron`, `0 0 2 1 * ?` — 02:00 on the 1st) capitalizes every
 * `(account, product)` with a pending `ACCRUING` set up to today ([CapitalizeInterestUseCase.capitalizeAll]).
 * `SKIP` on concurrent execution means a slow run is never doubled up; each per-pair capitalization
 * claims its accruals `ACCRUING → CAPITALIZING` under a ledger idempotency key, so a retry (or a manual
 * `POST /capitalize/all`) after a partial run only finishes the pairs that were still pending.
 *
 * This is the engine that was previously missing: `capitalizeAll` used to be a stub returning 0, so no
 * interest was ever capitalized — and therefore no withholding tax was ever assembled or remitted
 * (issue #999), despite the accrual, capitalization, withholding and remittance machinery all existing.
 * It mirrors [InterestAccrualScheduler], one rung later in the lifecycle.
 */
@ApplicationScoped
class InterestCapitalizationScheduler(
    private val capitalizeInterestUseCase: CapitalizeInterestUseCase,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(InterestCapitalizationScheduler::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofHours(APPROX_MONTHLY_HOURS))
    }

    @Scheduled(
        cron = "{openbank.interest.capitalization-cron}",
        identity = "interest-monthly-capitalization",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun runMonthlyCapitalization(): Uni<Void> {
        val toDate = LocalDate.now(clock)
        log.infof("interest capitalization tick starting up to %s", toDate)
        // observed-by: the `interest-capitalization` workflow-liveness gauge. `recordSuccess()` below runs on the
        // onItem path only, so a failed tick leaves
        // `openbank_workflow_last_success_age_seconds{workflow="interest-capitalization"}` climbing and trips
        // ADR-0237's WorkflowLivenessStale at 2x the monthly interval. Recovering the item is
        // deliberate rather than lazy: `capitalizeAll` is idempotent per (account, period), so the next tick (or a
        // manual re-run) fills the gap, and failing the Uni would buy nothing the gauge does not
        // already say. #5745 section C.
        return capitalizeInterestUseCase.capitalizeAll(toDate)
            .onItem().invoke { count ->
                log.infof("interest capitalization up to %s capitalized %d pair(s)", toDate, count)
                liveness?.recordSuccess()
            }
            .onFailure().invoke { e -> log.errorf(e, "interest capitalization up to %s failed", toDate) }
            .onFailure().recoverWithItem(0)
            .replaceWithVoid()
    }

    private companion object {
        const val APPROX_MONTHLY_HOURS = 720L
        const val WORKFLOW_NAME = "interest-capitalization"
    }
}
