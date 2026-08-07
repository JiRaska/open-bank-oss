// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.`in`.RunProvisioningCycleUseCase
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The IFRS 9 provisioning posting loop (ADR-0028 Phase 3), structured identically to
 * [InterestAccrualScheduler]: an injected [Clock] (ADR-0100 — money-path services never call
 * `Instant.now()`/`LocalDate.now()` directly), a config-driven interval and batch size, and
 * `concurrentExecution = SKIP` so overlapping runs never race.
 *
 * Each tick re-buckets every ACTIVE loan's IFRS 9 stage/ECL for the **current calendar month**
 * (`yyyy-MM`, derived from the injected clock — never wall-clock time) and posts only the delta versus
 * the loan's previous period. A loan already provisioned for the current period is a no-op re-read
 * (idempotent), so running this more than once within the same month is safe.
 *
 * PD/LGD are the conservative placeholders from `ConservativeRiskParameterSource` until a real
 * risk-parameter adapter is bound (ADR-0028 D4) — the ECL this loop posts is **not** production-grade
 * regulatory capital; see the ADR and PR description for the explicit calibration caveat.
 */
@ApplicationScoped
class ProvisioningCycleScheduler(
    private val cycle: RunProvisioningCycleUseCase,
    @ConfigProperty(name = "lending.provisioning.cycle.batch-size", defaultValue = "500")
    private val batchSize: Int,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(ProvisioningCycleScheduler::class.java)
    private val periodFormat = DateTimeFormatter.ofPattern("yyyy-MM")

    // Nullable, not `lateinit`: the gauge is a diagnostic, and a money-path job must never fail
    // because its observability wiring was not initialised. `lateinit` turns a missed StartupEvent
    // into an UninitializedPropertyAccessException thrown from the middle of the run.
    private var liveness: WorkflowLivenessRecorder? = null

    // ADR-0160 mechanism 3. Registered once at startup (CDI beans are singletons), not per-run.
    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofHours(APPROX_MONTHLY_HOURS))
    }

    @Scheduled(
        every = "{lending.provisioning.cycle.every}",
        delayed = "60s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun runProvisioningPass(): Uni<Void> = Panache.withSession {
        val asOf = LocalDate.now(clock)
        val period = asOf.format(periodFormat)
        cycle.runProvisioningCycle(period, asOf, batchSize)
            .invoke { outcome ->
                log.infof(
                    "IFRS 9 provisioning cycle %s: %d loans assessed, %d provisioning journals posted",
                    outcome.period,
                    outcome.loansAssessed,
                    outcome.journalsPosted,
                )
                // The batch scan (LoanRepository.findActive) has no continuation cursor: if the active
                // book is exactly at (or over) the batch size, this tick may have silently left loans
                // unprovisioned for the period. Flag it — the next tick's idempotency check means a
                // truncated tail self-heals eventually, but an operator should know it's happening.
                if (outcome.loansAssessed >= batchSize) {
                    log.warnf(
                        "IFRS 9 provisioning cycle %s assessed %d loans, at or above the batch size " +
                            "(%d) — the active loan book may exceed one pass and some loans could be " +
                            "left unprovisioned for this period until a later tick catches up",
                        outcome.period,
                        outcome.loansAssessed,
                        batchSize,
                    )
                }
                // ADR-0160 mechanism 3: record after the success path — a truncated pass that logs a
                // warning is still a successful run of the control; never record in the failure path.
                liveness?.recordSuccess()
            }
            .onFailure().invoke { e -> log.error("IFRS 9 provisioning cycle failed", e) }
            .replaceWithVoid()
    }

    private companion object {
        /** ADR-0160 mechanism 3 workflow tag — stable, low-cardinality. */
        const val WORKFLOW_NAME = "lending-provisioning-cycle"

        /** Approximate monthly interval (720 h) matching `lending.provisioning.cycle.every` default. */
        const val APPROX_MONTHLY_HOURS = 720L
    }
}
