// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.fx.infrastructure.schedule

import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.IngestCnbFixingCommand
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import kotlinx.coroutines.runBlocking

/**
 * Daily job that ingests the ČNB central-bank fixing shortly after its ~14:30 Europe/Prague
 * publication (ADR-0046: 14:40). Ingestion is idempotent per business day, so a missed or repeated
 * run is harmless. Failures are logged and swallowed — the scheduler must never crash, and the
 * manual `POST /api/v1/fx/cnb/ingest` endpoint covers backfill.
 */
@ApplicationScoped
class CnbRateIngestionScheduler(
    private val useCase: CnbRateIngestionUseCase
) {
    private val log = Logger.getLogger(CnbRateIngestionScheduler::class.java)

    @Scheduled(
        cron = "0 40 14 * * ?",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    fun ingestDailyFixing() = runBlocking {
        try {
            val result = useCase.ingest(IngestCnbFixingCommand(date = null))
            log.infof(
                "ČNB fixing ingested for %s (#%s): %d new, %d unchanged %s",
                result.date, result.sequence, result.ingested, result.skipped, result.currencies
            )
        } catch (ex: Exception) {
            log.errorf(ex, "ČNB fixing ingestion failed: %s", ex.message)
        }
    }
}
