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
    // The cap on batches per tick, NOT on loans per period. It exists so one pass cannot run
    // unbounded against a runaway book; it is not how the period gets covered. See the drain below.
    @ConfigProperty(name = "lending.provisioning.cycle.max-batches", defaultValue = "40")
    private val maxBatches: Int,
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
        drain(period, asOf, batchesRun = 0, loansAssessed = 0, journalsPosted = 0)
            .invoke { total ->
                log.infof(
                    "IFRS 9 provisioning cycle %s: %d loans assessed in %d batch(es), %d journals posted",
                    period,
                    total.loansAssessed,
                    total.batchesRun,
                    total.journalsPosted,
                )
                if (total.batchesRun >= maxBatches) {
                    // The one case that is NOT covered: the drain stopped on its own cap, so loans
                    // remain unprovisioned for this period and the next tick is 720h away by default.
                    log.warnf(
                        "IFRS 9 provisioning cycle %s stopped at the %d-batch cap after %d loans — " +
                            "loans remain UNPROVISIONED for this period and the next tick is one " +
                            "cycle interval away. Raise lending.provisioning.cycle.max-batches " +
                            "against the active book's actual size (%d batches covers %d loans).",
                        period,
                        maxBatches,
                        total.loansAssessed,
                        maxBatches,
                        maxBatches.toLong() * batchSize,
                    )
                }
                liveness?.recordSuccess()
            }
            .onFailure().invoke { e -> log.error("IFRS 9 provisioning cycle failed", e) }
            .replaceWithVoid()
    }

    /**
     * Provision the WHOLE period in this tick, one batch at a time, stopping when a batch comes back
     * short (the book is exhausted) or at [maxBatches].
     *
     * The loop is the point, not a nicety. The scan excludes loans already provisioned for the
     * period, so a batch-at-a-time cycle does advance — but the schedule is
     * `lending.provisioning.cycle.every`, **720h by default**. One batch per tick would cover 500
     * loans a month: a 5,000-loan book would take ten months to finish a single period's
     * provisioning, which is indistinguishable from the #9901 defect at any horizon a regulator
     * cares about. Sliding the window fixes the direction; draining it fixes the rate.
     *
     * Recursion rather than a Multi: each pass must observe the rows the previous one wrote, so the
     * batches are strictly sequential. They share this tick's session (Panache.withSession above).
     */
    private fun drain(
        period: String,
        asOf: LocalDate,
        batchesRun: Int,
        loansAssessed: Int,
        journalsPosted: Int,
    ): Uni<DrainOutcome> {
        if (batchesRun >= maxBatches) {
            return Uni.createFrom().item(DrainOutcome(batchesRun, loansAssessed, journalsPosted))
        }
        return cycle.runProvisioningCycle(period, asOf, batchSize).flatMap { outcome ->
            val batches = batchesRun + 1
            val assessed = loansAssessed + outcome.loansAssessed
            val posted = journalsPosted + outcome.journalsPosted
            if (outcome.loansAssessed < batchSize) {
                // Short batch: nothing unprovisioned is left for this period.
                Uni.createFrom().item(DrainOutcome(batches, assessed, posted))
            } else {
                drain(period, asOf, batches, assessed, posted)
            }
        }
    }

    /** What one tick did in total, across however many batches it took. */
    private data class DrainOutcome(val batchesRun: Int, val loansAssessed: Int, val journalsPosted: Int)

    private companion object {
        /** ADR-0160 mechanism 3 workflow tag — stable, low-cardinality. */
        const val WORKFLOW_NAME = "lending-provisioning-cycle"

        /** Approximate monthly interval (720 h) matching `lending.provisioning.cycle.every` default. */
        const val APPROX_MONTHLY_HOURS = 720L
    }
}
