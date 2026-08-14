// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.retention

import com.openbank.cardissuance.application.port.out.CardRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Enforces the card PII retention policy (ADR-0118 §5, AML Act §16).
 *
 * Cards whose expiry date is older than [retentionYears] years have their cardholder PII
 * (cardholderName, embossedName) anonymised in-place. The card row itself is kept for
 * AML audit purposes (the masked PAN and transaction history remain in ledger/transaction-service).
 *
 * Run daily at 03:00 UTC (configurable). `concurrentExecution = SKIP` prevents overlap
 * on slow runs. Enable [dryRun] to log the count without committing changes.
 *
 * ## Liveness heartbeat (ADR-0237)
 *
 * This sweep is the only thing enforcing an AML-mandated anonymisation deadline, and it fails
 * silently: nothing throws on a schedule that stopped firing (the `HR000068` class behind #2148 /
 * #2187), and "0 cards anonymised" is also what a healthy quiet day looks like. So a permanently
 * dead sweep is indistinguishable from a clean one, while PII that should have been anonymised
 * years ago stays on disk. [DomainMetrics.registerWorkflowLiveness] publishes the last-success age
 * for the ADR-0237 staleness rule and `openbank-control-liveness-sentinel`.
 *
 * **The gauge is registered only when the sweep will actually anonymise** — [enabled] and not
 * [dryRun] — because a heartbeat from a disabled or preview-only sweep asserts exactly the
 * retention it is not performing. Absent is the honest signal for "this environment does not run
 * this job", and it is a *different* state from stale; the staleness rule alerts on stale, never
 * on absent. With the defaults here ([enabled] `true`, [dryRun] `false`) the gauge is registered.
 *
 * Registration hangs off [StartupEvent] rather than `@PostConstruct` because `@ApplicationScoped`
 * is lazy: a `@PostConstruct` would first run when the cron first fires, up to a day after boot,
 * leaving the gauge absent for that whole window — and absent is not the same signal as stale.
 */
@ApplicationScoped
class CardPiiRetentionScheduler(
    private val cardRepository: CardRepository,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.retention.card-pii.retention-years", defaultValue = "5")
    private val retentionYears: Long,
    @ConfigProperty(name = "openbank.retention.card-pii.dry-run", defaultValue = "false")
    private val dryRun: Boolean,
    @ConfigProperty(name = "openbank.retention.card-pii.enabled", defaultValue = "true")
    private val enabled: Boolean,
    private val domainMetrics: DomainMetrics,
) {

    private val log = Logger.getLogger(CardPiiRetentionScheduler::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled && !dryRun) {
            liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        }
    }

    @Scheduled(
        cron = "{openbank.retention.card-pii.cron:0 0 3 * * ?}",
        concurrentExecution = SKIP,
    )
    suspend fun enforceRetention() {
        if (!enabled) return

        val cutoff = LocalDate.now(clock).minusYears(retentionYears)

        if (dryRun) {
            log.infof(
                "[retention] DRY-RUN: would anonymise card PII for cards with expiry_date < %s (retention %dy)",
                cutoff,
                retentionYears,
            )
            return
        }

        val count = cardRepository.anonymizeExpiredCardPii(cutoff)
        // Recorded for a zero-row run too: an empty sweep IS a successful sweep, and withholding
        // the heartbeat on a quiet day would make a healthy schedule read as stale.
        liveness?.recordSuccess()
        if (count > 0) {
            log.infof(
                "[retention] Anonymised PII for %d card(s) with expiry_date < %s (ADR-0118 §5)",
                count,
                cutoff,
            )
        }
    }

    companion object {
        private const val WORKFLOW_NAME = "card-pii-retention"
        private val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
