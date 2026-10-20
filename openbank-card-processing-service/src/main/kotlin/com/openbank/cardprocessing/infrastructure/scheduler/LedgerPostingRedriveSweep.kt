// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheduler

import com.openbank.cardprocessing.application.usecase.LedgerPostingRedriver
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.CancellationException
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives [LedgerPostingRedriver] (#13228): cleared card spend whose ledger posting failed or was
 * unreadable is posted again until a completed posting is confirmed.
 *
 * A **`suspend fun`** for the reason in [HoldExpirySweep]: a plain `@Scheduled` method has no Vert.x
 * context, so a reactive repository call from it throws `HR000068` and the job silently never runs.
 * The coverage that matters is `LedgerPostingRedriveIT`, which re-enables the scheduler under a
 * `@TestProfile` and shrinks the cron.
 *
 * ## What it publishes
 *  - the ADR-0237 liveness heartbeat, recorded only when a tick actually returned (a heartbeat on the
 *    failure branch would assert the very thing it exists to disprove);
 *  - `openbank.card.processing.ledger.posting.unposted` - clearings without a confirmed completed posting;
 *  - `openbank.card.processing.ledger.posting.exhausted` - clearings that used up their attempts and
 *    are no longer retried. **This is the alert**: `> 0` means a completed posting is unconfirmed
 *    and a human has to reconcile its outcome. Exhausted rows are kept, never dropped.
 */
@ApplicationScoped
class LedgerPostingRedriveSweep(
    private val redriver: LedgerPostingRedriver,
    private val domainMetrics: DomainMetrics,
    registryInstance: Instance<MeterRegistry>,
) {
    private val log = Logger.getLogger(LedgerPostingRedriveSweep::class.java)

    private var liveness: WorkflowLivenessRecorder? = null
    private val unposted = AtomicLong(0)
    private val exhausted = AtomicLong(0)

    init {
        if (registryInstance.isResolvable) {
            val registry = registryInstance.get()
            val tags = listOf(Tag.of("service", SERVICE))
            registry.gauge(UNPOSTED_GAUGE, tags, unposted)
            registry.gauge(EXHAUSTED_GAUGE, tags, exhausted)
        }
    }

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        cron = "\${openbank.card-processing.posting-redrive-cron:0 * * * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "card-processing-ledger-posting-redrive",
    )
    suspend fun sweep() {
        // Work is selected by STATE (unposted rows whose backoff elapsed), so a later tick can retry
        // rows after a transient failure. Guard each phase independently: failure in the first must
        // not prevent the second phase from running.
        // The two halves are independent: a failing re-drive must not freeze the gauges (the exhausted
        // gauge is the alert), and a failing gauge refresh must not hide a working re-drive.
        // observed-by: the heartbeat below is recorded only when BOTH halves returned, so either kind of
        // failure surfaces as WorkflowLivenessStale.
        val redriven = guarded("ledger posting re-drive") {
            val result = redriver.redriveDue()
            if (result.attempted > 0) log.infof("re-drove %d ledger posting(s)", result.attempted)
            if (result.skippedDisabled > 0) {
                log.warnf(
                    "%d clearing(s) not re-driven: ledger posting is switched off " +
                        "(openbank.card-processing.ledger-posting-enabled=false)",
                    result.skippedDisabled,
                )
            }
        }
        val refreshed = guarded("ledger posting backlog refresh") {
            val backlog = redriver.backlog()
            unposted.set(backlog.unposted)
            exhausted.set(backlog.exhausted)
            if (backlog.exhausted > 0) {
                log.errorf(
                    "%d clearing(s) exhausted their ledger posting attempts - completed postings are unconfirmed",
                    backlog.exhausted,
                )
            }
        }
        if (redriven && refreshed) liveness?.recordSuccess()
    }

    /** Runs [block]; cancellation propagates (a `runCatching` would swallow it), anything else is logged. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun guarded(what: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.errorf("%s failed (failureType=%s)", what, e.javaClass.simpleName)
        false
    }

    private companion object {
        const val WORKFLOW_NAME = "card-processing-ledger-posting-redrive"
        const val SERVICE = "card-processing"
        const val UNPOSTED_GAUGE = "openbank.card.processing.ledger.posting.unposted"
        const val EXHAUSTED_GAUGE = "openbank.card.processing.ledger.posting.exhausted"

        /** Matches the default cron above (every minute). */
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
