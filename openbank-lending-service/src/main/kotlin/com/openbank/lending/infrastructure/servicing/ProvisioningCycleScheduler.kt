// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.`in`.RunProvisioningCycleUseCase
import com.openbank.lending.application.port.out.CloseJobRunPort
import com.openbank.lending.application.port.out.ProvisioningCoverageRepository
import com.openbank.lending.application.port.out.ProvisioningCycleRunRepository
import com.openbank.lending.domain.model.ProvisioningRunOutcome
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * The IFRS 9 provisioning posting loop (ADR-0028 Phase 3), structured identically to
 * [InterestAccrualScheduler]: an injected [Clock] (ADR-0100 — money-path services never call
 * `Instant.now()`/`LocalDate.now()` directly), a config-driven interval and batch size, and
 * `concurrentExecution = SKIP` so overlapping runs never race.
 *
 * Each tick re-buckets every eligible exposure's IFRS 9 stage/ECL for the **current reporting date**
 * (`yyyy-MM-dd`, derived from the injected clock — never wall-clock time) and posts only the delta versus
 * the loan's previous period. A loan already provisioned for the current period is a no-op re-read
 * (idempotent), so running this more than once for the same date is safe.
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
    private val coverage: ProvisioningCoverageRepository,
    private val runs: ProvisioningCycleRunRepository,
    private val registry: MeterRegistry?,
    private val closeJobRuns: CloseJobRunPort,
) {
    // Explicit @Inject constructor: MeterRegistry is optional (absent in slim test slices), and with
    // two constructors and no annotation ArC registers no bean at all — the trap documented on
    // OrphanedPartyGauge and DomesticPaymentStrandedGauge.
    @Inject
    constructor(
        cycle: RunProvisioningCycleUseCase,
        @ConfigProperty(name = "lending.provisioning.cycle.batch-size", defaultValue = "500")
        batchSize: Int,
        clock: Clock,
        domainMetrics: DomainMetrics,
        coverage: ProvisioningCoverageRepository,
        runs: ProvisioningCycleRunRepository,
        registryInstance: Instance<MeterRegistry>,
        closeJobRuns: CloseJobRunPort,
    ) : this(
        cycle,
        batchSize,
        clock,
        domainMetrics,
        coverage,
        runs,
        if (registryInstance.isResolvable) registryInstance.get() else null,
        closeJobRuns,
    )

    private val log = Logger.getLogger(ProvisioningCycleScheduler::class.java)
    private val periodFormat = DateTimeFormatter.ISO_LOCAL_DATE

    // Nullable, not `lateinit`: the gauge is a diagnostic, and a money-path job must never fail
    // because its observability wiring was not initialised. `lateinit` turns a missed StartupEvent
    // into an UninitializedPropertyAccessException thrown from the middle of the run.
    private var liveness: WorkflowLivenessRecorder? = null

    // THE DETECTOR THIS DEFECT NEEDED (#9901). Nothing compared the count of eligible exposures with the
    // count of loans provisioned for the period, so a book silently missing its tail produced no
    // series anywhere that disagreed with a healthy one. The pass that fixes the coverage is also
    // the only place that already knows both numbers, so it publishes them.
    //
    // Seeded to 0 and only ever set by a COMPLETED pass, which makes a cold pod read 0/0/0 rather
    // than alarming (the ADR-0237 boot-zero rule). "Never ran" is told apart from "nothing to do" by
    // the workflow-liveness heartbeat above, not by these.
    private val eligibleLoans = AtomicLong(0)
    private val provisionedThisPeriod = AtomicLong(0)
    private val unprovisioned = AtomicLong(0)
    private val unresolvedPriorDays = AtomicLong(0)

    // ADR-0160 mechanism 3. Registered once at startup (CDI beans are singletons), not per-run.
    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofHours(DAILY_HOURS))
        val r = registry ?: return
        gauge(r, "openbank.lending.provisioning.eligible.loans", eligibleLoans)
        gauge(r, "openbank.lending.provisioning.rows.period", provisionedThisPeriod)
        gauge(r, "openbank.lending.provisioning.unprovisioned", unprovisioned)
        gauge(r, "openbank.lending.provisioning.unresolved.prior.days", unresolvedPriorDays)
    }

    private fun gauge(r: MeterRegistry, name: String, holder: AtomicLong) {
        Gauge.builder(name, holder) { it.get().toDouble() }
            .tag("service", METRICS_SERVICE_TAG)
            .strongReference(true)
            .register(r)
    }

    /**
     * Count missing eligible exposures directly after the drain. Total period rows also include closed loans.
     *
     * Deliberately AFTER the pass, not before: the question is whether the period is covered once
     * the cycle has done what it can, and a count taken first would report the backlog the pass was
     * about to clear. A non-zero shortfall here means either the batch cap truncated the drain, or
     * loans were activated during the pass — both worth seeing, neither self-announcing today.
     *
     * Failure to count cannot undo already-committed provisioning. It preserves the previous gauge
     * values, but cannot certify the period as complete, so workflow liveness records no success.
     */
    private fun publishCoverage(period: String): Uni<Long?> =
        coverage.countEligibleForProvisioning().flatMap { active ->
            coverage.countForPeriod(period).flatMap { covered ->
                coverage.countUnprovisioned(period).map { missing ->
                    eligibleLoans.set(active)
                    provisionedThisPeriod.set(covered)
                    unprovisioned.set(missing)
                    if (missing > 0) {
                        log.warnf(
                            "IFRS 9 provisioning coverage for %s: %d of %d eligible exposures have no " +
                                "provisioning row after this pass — the period is NOT fully provisioned",
                            period,
                            missing,
                            active,
                        )
                    }
                    val result: Long? = missing
                    result
                }
            }
        }.onFailure().recoverWithItem { e ->
            log.warnf(e, "IFRS 9 provisioning coverage could not be counted; leaving the previous gauge values")
            null
        }

    @Scheduled(
        every = "{lending.provisioning.cycle.every}",
        delayed = "60s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun runProvisioningPass(): Uni<Void> = Panache.withSession {
        // The Prague accounting day (ADR-0207): the reporting date and the day-close evidence agree.
        val asOf = AccountingClock.bank(clock).today()
        val period = asOf.format(periodFormat)
        // In-flight marker first (ADR-0342): while it stands the reporter does not report this day.
        LendingCloseJobs.markStarted(closeJobRuns, clock, asOf, LendingCloseJobs.PROVISIONING)
            // Commit the start BEFORE any loan-level transaction. An abrupt stop leaves durable RUNNING
            // evidence; a later date cannot certify that earlier reporting date as complete.
            .flatMap { runs.markStarted(asOf, OffsetDateTime.now(clock)) }
            .flatMap { attemptId ->
                runs.countUnresolvedBefore(asOf)
                    .invoke { unresolved ->
                        unresolvedPriorDays.set(unresolved)
                        if (unresolved > 0) {
                            log.errorf("IFRS 9 provisioning has %d unresolved prior reporting day(s)", unresolved)
                        }
                    }
                    .flatMap { unresolved ->
                        cycle.runProvisioningCycle(period, asOf, batchSize)
                            .invoke { outcome ->
                                log.infof(
                                    "IFRS 9 provisioning cycle %s: %d loans assessed, %d allowance commands queued",
                                    outcome.period,
                                    outcome.loansAssessed,
                                    outcome.journalsQueued,
                                )
                            }
                            // The outcome travels down the chain with the coverage count: no bean field
                            // to go stale between passes.
                            .flatMap { outcome -> publishCoverage(period).map { missing -> outcome to missing } }
                            .flatMap { (outcome, missing) ->
                                runs.markResult(asOf, attemptId, missing, OffsetDateTime.now(clock))
                                    .replaceWith(outcome to missing)
                            }
                            .call { (outcome, missing) -> recordCloseEvidence(asOf, outcome, missing) }
                            .map { (_, missing) -> missing == 0L && unresolved == 0L }
                    }
            }
            .invoke { complete -> if (complete) liveness?.recordSuccess() }
            .onFailure().invoke { e -> log.error("IFRS 9 provisioning cycle failed", e) }
            .onFailure().call { _ ->
                LendingCloseJobs.record(
                    closeJobRuns,
                    clock,
                    asOf,
                    LendingCloseJobs.PROVISIONING,
                    items = 0,
                    journals = 0,
                    refused = 1,
                    reason = LendingCloseJobs.REASON_PROVISIONING_FAILED,
                )
            }
            .replaceWithVoid()
    }

    /**
     * Day-close evidence (ADR-0342 D1). The allowance journals this pass queues are dispatched by the
     * outbox, not booked here, so the run records `journalsPosted = 0` and loans assessed as items;
     * [LendingCloseParticipationScheduler] adds the day's allowance journals once the outbox has
     * delivered them, and does not report the day while any is still pending. A pass that leaves
     * eligible exposures unprovisioned, or whose coverage could not be counted, cannot certify the day:
     * it is recorded REFUSED with the shortfall, which holds the ledger's close until a complete pass
     * (or an operator waiver) replaces it.
     */
    private fun recordCloseEvidence(asOf: LocalDate, outcome: ProvisioningRunOutcome, missing: Long?): Uni<Unit> {
        val incomplete = missing == null || missing > 0
        return LendingCloseJobs.record(
            closeJobRuns,
            clock,
            asOf,
            LendingCloseJobs.PROVISIONING,
            items = outcome.loansAssessed,
            journals = 0,
            refused = if (incomplete) (missing ?: 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
            reason = when {
                missing == null -> LendingCloseJobs.REASON_PROVISIONING_UNCOUNTED
                missing > 0 -> LendingCloseJobs.REASON_PROVISIONING_INCOMPLETE
                else -> null
            },
        )
    }

    private companion object {
        const val METRICS_SERVICE_TAG = "lending"

        /** ADR-0160 mechanism 3 workflow tag — stable, low-cardinality. */
        const val WORKFLOW_NAME = "lending-provisioning-cycle"

        /** Daily interval (24 h) matching `lending.provisioning.cycle.every` default. */
        const val DAILY_HOURS = 24L
    }
}
