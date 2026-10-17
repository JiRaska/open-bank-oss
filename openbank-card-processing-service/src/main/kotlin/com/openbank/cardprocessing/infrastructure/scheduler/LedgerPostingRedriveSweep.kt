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
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives [LedgerPostingRedriver] (#13228): cleared card spend whose ledger posting failed or was
 * unreadable is posted again until the books have it.
 *
 * A **`suspend fun`** for the reason in [HoldExpirySweep]: a plain `@Scheduled` method has no Vert.x
 * context, so a reactive repository call from it throws `HR000068` and the job silently never runs.
 * The coverage that matters is `LedgerPostingRedriveIT`, which re-enables the scheduler under a
 * `@TestProfile` and shrinks the cron.
 *
 * ## What it publishes
 *  - the ADR-0237 liveness heartbeat, recorded only when a tick actually returned (a heartbeat on the
 *    failure branch would assert the very thing it exists to disprove);
 *  - `openbank.card.processing.ledger.posting.unposted` - clearings still owed a posting;
 *  - `openbank.card.processing.ledger.posting.exhausted` - clearings that used up their attempts and
 *    are no longer retried. **This is the alert**: `> 0` means a debit is not in the books and a human
 *    has to act. Exhausted rows are kept, never dropped.
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
        // best-effort per tick: the work is selected by STATE (unposted rows whose backoff elapsed), so a
        // tick that fails re-selects the same rows next minute and loses nothing; letting the exception
        // escape would kill the schedule, which is the one outcome that does lose the work.
        // observed-by: the liveness gauge stops refreshing on this path -> WorkflowLivenessStale.
        runCatching {
            val attempted = redriver.redriveDue()
            val backlog = redriver.backlog()
            unposted.set(backlog.unposted)
            exhausted.set(backlog.exhausted)
            if (attempted > 0) {
                log.infof("re-drove %d ledger posting(s); %d still unposted", attempted, backlog.unposted)
            }
            if (backlog.exhausted > 0) {
                log.errorf(
                    "%d clearing(s) exhausted their ledger posting attempts - the books are missing them",
                    backlog.exhausted,
                )
            }
        }
            .onSuccess { liveness?.recordSuccess() }
            .onFailure { log.error("ledger posting re-drive failed", it) }
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
