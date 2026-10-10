// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit

import com.openbank.libs.observability.DomainMetrics
import com.openbank.pension.application.exit.ExitWorkflowLauncher
import com.openbank.pension.application.exit.PayoutService
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.runtime.configuration.DurationConverter
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Liveness control for phased withdrawals and fixed-period pensions. The installments are paid by
 * durable Temporal timers; this sweep is the independent check that they ARE being paid: any
 * scheduled payout with an installment past due is counted (gauge
 * `openbank_pension_payout_installments_overdue`) and its workflow re-started — a no-op when the
 * workflow is alive (workflow id = payout id), a recovery when it was lost.
 *
 * A `suspend fun`, never `runBlocking`: a plain @Scheduled method has no Vert.x context and the
 * first reactive Panache call would abort the job silently (`rules.yaml: scheduled_methods`).
 */
@Startup
@ApplicationScoped
class PayoutScheduleSweep(
    private val payouts: PayoutService,
    private val launcher: ExitWorkflowLauncher,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
    domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.pension.payout-sweep.every", defaultValue = "1h")
    private val interval: String,
) {
    private val log = Logger.getLogger(PayoutScheduleSweep::class.java)
    private val overdue = AtomicInteger(0)
    private val liveness = if (
        interval.equals("off", ignoreCase = true) || interval.equals("disabled", ignoreCase = true)
    ) {
        null
    } else {
        domainMetrics.registerWorkflowLiveness(
            "pension-payout-schedule-sweep",
            DurationConverter.parseDuration(interval),
        )
    }

    init {
        meterRegistry.gauge("openbank_pension_payout_installments_overdue", overdue)
    }

    @Scheduled(
        every = "\${openbank.pension.payout-sweep.every:1h}",
        delayed = "\${openbank.pension.payout-sweep.delay:5m}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "pension-payout-schedule-sweep",
    )
    suspend fun sweep() {
        val stale = payouts.overdue(LocalDate.now(clock))
        overdue.set(stale.size)
        stale.forEach {
            log.warnf("payout %s has an overdue installment; re-starting its workflow", it.id)
            launcher.startPayout(it.id)
        }
        liveness?.recordSuccess()
    }
}
