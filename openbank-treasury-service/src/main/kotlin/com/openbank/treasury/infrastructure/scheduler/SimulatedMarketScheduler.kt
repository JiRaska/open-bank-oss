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
import java.time.Duration

/**
 * The SIMULATED counterparty set (ADR-0315 D9) — SYNTHETIC, sandbox only. It stands in for the
 * counterparty's confirmation and the payment system's settlement: every BOOKED deal whose value
 * date has come is settled, every SETTLED deal whose maturity has come is matured, each posting
 * its journal through the ledger API exactly as a human approver's explicit settle would. Real
 * confirmation matching and dealing-platform connectivity are out of scope (ADR-0313).
 *
 * A `suspend fun` on purpose: a plain `@Scheduled` method has no Vert.x context and a
 * `runBlocking` around reactive Panache throws HR000068 (#2148; check-no-runblocking-in-scheduled).
 */
@ApplicationScoped
class SimulatedMarketScheduler(
    private val deals: TreasuryDealUseCase,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.treasury.simulated-market.enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    private val log: Logger = Logger.getLogger(SimulatedMarketScheduler::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    /** Registered at boot, not lazily on first fire: an absent gauge is not a stale one (ADR-0237). */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "\${openbank.treasury.simulated-market.interval:60s}",
        delayed = "\${openbank.treasury.simulated-market.initial-delay:30s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "treasury-simulated-market",
    )
    suspend fun run() {
        if (!enabled) return
        runCatching { deals.runSimulatedMarket() }
            .onSuccess { r ->
                r.failures.forEach { log.error("simulated market could not move a deal", it) }
                if (r.failures.isEmpty()) liveness?.recordSuccess()
                if (r.moved > 0) log.infof("simulated market moved %d deal(s)", r.moved)
            }
            .onFailure { log.error("simulated market pass failed", it) }
    }

    private companion object {
        const val WORKFLOW_NAME = "treasury-simulated-market"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
