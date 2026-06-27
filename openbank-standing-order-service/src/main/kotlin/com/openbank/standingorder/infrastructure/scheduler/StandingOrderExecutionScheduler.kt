// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.scheduler

import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate

/**
 * Daily execution sweep — finds all ACTIVE standing orders due on or before today and emits
 * a [standing-order.due.v1] outbox event for each, atomically advancing nextExecutionDate.
 *
 * The scheduler runs at 03:00 UTC (configurable via openbank.scheduler.execution-cron) with
 * SKIP concurrency so a slow sweep does not overlap with the next day's run.
 *
 * Downstream payment rails (sepa-payment, domestic-payment) consume standing-order.due.v1
 * events; idempotency is keyed on "so-exec-{orderId}-{executionDate}" so redeliveries are safe.
 */
@ApplicationScoped
class StandingOrderExecutionScheduler {

    @Inject
    lateinit var standingOrderUseCase: StandingOrderUseCase

    @Inject
    lateinit var clock: Clock

    @ConfigProperty(name = "openbank.scheduler.execution-enabled", defaultValue = "true")
    var enabled: Boolean = true

    private val log = Logger.getLogger(StandingOrderExecutionScheduler::class.java)

    @Scheduled(
        cron = "{openbank.scheduler.execution-cron}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun sweep(): Unit = runBlocking {
        if (!enabled) {
            log.debug("[execution-scheduler] Disabled — skipping sweep")
            return@runBlocking
        }
        val today = LocalDate.now(clock)
        log.infof("[execution-scheduler] Starting daily execution sweep for %s", today)
        val count = standingOrderUseCase.executeOrders(today)
        log.infof("[execution-scheduler] Daily execution sweep done: %d orders scheduled", count)
    }
}
