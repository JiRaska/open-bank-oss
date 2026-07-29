// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.scheduler

import com.openbank.statement.application.port.`in`.RunCloseUseCase
import com.openbank.statement.domain.model.CloseTrigger
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Drives the scheduled monthly **period-close** cadence (ADR-0035 §F.1, ADR-0069 D3). The actual
 * work — self-healing catch-up enumeration, fail-closed reconciliation, run-outcome persistence and
 * `period.close_failed` emission — lives in [RunCloseUseCase] (CloseOrchestrator). This adapter only
 * wires the cron trigger to it.
 *
 * The cadence stays **disabled by default** ([enabled]); flip
 * `openbank.statement.scheduled-close.enabled=true` once the registry has back-filled and the
 * close-run telemetry/alerts are in place. The on-demand `POST /{accountId}/close` endpoint and the
 * operator `POST /api/v1/statements/close-runs` retry remain available regardless.
 */
@ApplicationScoped
class PeriodCloseScheduler(
    private val runClose: RunCloseUseCase,
    @ConfigProperty(name = "openbank.statement.scheduled-close.enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    private val log = Logger.getLogger(PeriodCloseScheduler::class.java)

    // Europe/Prague is explicit, not incidental (#1302): an unset @Scheduled timeZone means
    // JVM-default, so the close fires on the pod's zone, not the bank's accounting day —
    // the third clock regime from the closing audit. Prague matches ledger's BANK_TIME, so
    // the month-end close runs on the same day the ledger closed.
    @Scheduled(
        cron = "{openbank.statement.close-cron}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun monthlyClose(): Uni<Void> {
        if (!enabled) {
            log.debug("Scheduled period-close disabled; use POST /{accountId}/close or the operator retry")
            return Uni.createFrom().voidItem()
        }
        return runClose.runClose(CloseTrigger.SCHEDULED).replaceWithVoid()
    }
}
