// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.scheduler

import com.openbank.interest.application.port.`in`.AccrueInterestUseCase
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
 * Drives the daily interest accrual. One tick per day (cron in `openbank.interest.accrual.cron`)
 * posts a day's accrual for every ACTIVE interest-bearing account
 * ([AccrueInterestUseCase.accrueAll]). `SKIP` on concurrent execution means a slow run is never
 * doubled up, and the accrual itself is idempotent per `(account, date)`, so a retry (or a manual
 * `POST /accrue/all`) after a partial run only fills the gaps — never double-credits.
 *
 * This is the engine that was previously missing: `accrueAll` used to be a stub returning 0, so no
 * interest was ever accrued despite the rate/accrual/capitalization machinery all existing.
 */
@ApplicationScoped
class InterestAccrualScheduler(
    private val accrueInterestUseCase: AccrueInterestUseCase,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(InterestAccrualScheduler::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofDays(1))
    }

    @Scheduled(
        cron = "{openbank.interest.accrual-cron}",
        identity = "interest-daily-accrual",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun runDailyAccrual(): Uni<Void> {
        val date = LocalDate.now(clock)
        log.infof("interest accrual tick starting for %s", date)
        return accrueInterestUseCase.accrueAll(date)
            .onItem().invoke { count ->
                log.infof("interest accrual for %s wrote %d accrual(s)", date, count)
                liveness?.recordSuccess()
            }
            .onFailure().invoke { e -> log.errorf(e, "interest accrual for %s failed", date) }
            .onFailure().recoverWithItem(0)
            .replaceWithVoid()
    }

    private companion object {
        const val WORKFLOW_NAME = "interest-accrual"
    }
}
