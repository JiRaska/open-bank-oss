// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Daily interest accrual for SETTLED deals (ADR-0315 D5). Runs hourly rather than once a day on
 * purpose: every pass posts only the days not yet accrued (the per-day ledger key makes a repeat a
 * replay), so a missed or failed run is caught up by the next one instead of leaving a gap.
 *
 * A `suspend fun` on purpose: a plain `@Scheduled` method has no Vert.x context and a
 * `runBlocking` around reactive Panache throws HR000068 (#2148; check-no-runblocking-in-scheduled).
 */
@ApplicationScoped
class InterestAccrualScheduler(
    private val deals: TreasuryDealUseCase,
    private val domainMetrics: DomainMetrics,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.treasury.accrual.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {
    private val log: Logger = Logger.getLogger(InterestAccrualScheduler::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    /** Registered at boot, not lazily on first fire: an absent gauge is not a stale one (ADR-0237). */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "\${openbank.treasury.accrual.interval:1h}",
        delayed = "\${openbank.treasury.accrual.initial-delay:60s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "treasury-interest-accrual",
    )
    suspend fun run() {
        if (!enabled) return
        runCatching { deals.accrueInterest(LocalDate.now(clock)) }
            .onSuccess { r ->
                r.failures.forEach { log.error("interest accrual could not accrue a deal", it) }
                if (r.failures.isEmpty()) liveness?.recordSuccess()
                if (r.journals > 0) log.infof("interest accrual posted %d journal(s)", r.journals)
            }
            .onFailure { log.error("interest accrual pass failed", it) }
    }

    private companion object {
        const val WORKFLOW_NAME = "treasury-interest-accrual"
        val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
    }
}
