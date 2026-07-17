// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.application.port.`in`.FxRevaluationUseCase
import com.openbank.ledger.application.port.`in`.RevalueFxCommand
import com.openbank.libs.persistence.lock.ClusterLock
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily job that marks foreign FX positions to the ČNB fixing (ADR-0046) at 15:00 Europe/Prague —
 * after fx-service has ingested the day's fixing (~14:40). The revaluation is idempotent per
 * business day (`fx-reval-{date}` idempotency key), so a concurrent-revalue race loser gets the
 * winner's *posting* back safely — but it still separately publishes a second, non-outboxed
 * `openbank.ledger.fx.revalued` event and logs "posted" (#1201, L-12). `concurrentExecution =
 * SKIP` only stops in-JVM overlap; an Argo Rollouts canary window runs the old and new pod
 * simultaneously for the whole rollout, and the 15:00 run landing inside a deploy window is not
 * hypothetical — deploys happen during business hours. [ClusterLock.tryRunExclusively] wraps the
 * run in a transaction-scoped advisory lock so only one pod actually revalues and publishes per
 * day; the losing pod's tick is a no-op. A missed or repeated run is still harmless independent
 * of this — the manual `POST /api/v1/ledger/fx-revaluation` covers backfill — this only removes
 * the double-publish.
 */
@ApplicationScoped
class FxRevaluationScheduler(private val useCase: FxRevaluationUseCase, private val clusterLock: ClusterLock) {
    private val log: Logger = Logger.getLogger(FxRevaluationScheduler::class.java)
    private val zone: ZoneId = ZoneId.of("Europe/Prague")

    @Scheduled(
        cron = "0 0 15 * * ?",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun revalueDaily() = runBlocking {
        val ran = clusterLock.tryRunExclusively(JOB_NAME) {
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
        if (ran == null) {
            log.infof("Daily FX revaluation: another pod already holds this tick's lock — skipping")
        }
    }

    private companion object {
        const val JOB_NAME = "ledger.fx-revaluation"
    }
}
