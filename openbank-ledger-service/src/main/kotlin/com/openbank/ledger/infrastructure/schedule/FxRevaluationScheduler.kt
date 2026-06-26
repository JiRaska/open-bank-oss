// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.application.port.`in`.FxRevaluationUseCase
import com.openbank.ledger.application.port.`in`.RevalueFxCommand
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily job that marks foreign FX positions to the ČNB fixing (ADR-0046) at 15:00 Europe/Prague —
 * after fx-service has ingested the day's fixing (~14:40). The revaluation is idempotent per
 * business day, so a missed or repeated run is harmless; failures are logged and swallowed (the
 * scheduler must never crash) and the manual `POST /api/v1/ledger/fx-revaluation` covers backfill.
 */
@ApplicationScoped
class FxRevaluationScheduler(private val useCase: FxRevaluationUseCase) {
    private val log: Logger = Logger.getLogger(FxRevaluationScheduler::class.java)
    private val zone: ZoneId = ZoneId.of("Europe/Prague")

    @Scheduled(
        cron = "0 0 15 * * ?",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun revalueDaily() = runBlocking {
        try {
            val result = useCase.revalue(RevalueFxCommand(LocalDate.now(zone)))
            if (result.posted) {
                log.infof("Daily FX revaluation posted for %s: %s", result.date, result.movements)
            } else {
                log.infof("Daily FX revaluation for %s: no movement", result.date)
            }
        } catch (ex: Exception) {
            log.errorf(ex, "Daily FX revaluation failed: %s", ex.message)
        }
    }
}
