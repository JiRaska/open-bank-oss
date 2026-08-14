// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.retention

import com.openbank.audit.application.port.out.SessionLogRepositoryPort
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
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
import java.time.Instant

/**
 * Enforces the session/access-log retention policy (ADR-0118 §2/§5, issue #268): rows in
 * `session_logs` older than [retentionDays] (default 90, no specific statutory requirement —
 * proportionality only, unlike the AML-mandated KYC/card retention) are hard-deleted.
 *
 * ## Disabled by default — deliberate, not an oversight
 *
 * This scheduler ships with `openbank.retention.session-log.enabled` defaulting to **`false`**,
 * unlike [com.openbank.cardissuance.infrastructure.retention.CardPiiRetentionScheduler] and
 * `KycRetentionScheduler` (both `enabled=true` by default, already live on `main` since #2479 /
 * #2480). Those two were reviewed and enabled deliberately in their own PRs; this is the first
 * time session-log deletion exists at all, against a brand-new table with no production
 * history — merging this PR must not, by itself, cause any deletion in any environment. Turning
 * it on is a separate, deliberate follow-up decision per environment.
 *
 * [dryRun] additionally gates the delete independent of [enabled]: when true, the scheduler logs
 * a structured preview (row count that *would* be deleted) and emits a DRY_RUN [AuditEvent], but
 * never calls [SessionLogRepositoryPort.deleteOlderThan]. This lets an operator observe the
 * blast radius before flipping `enabled=true` for real.
 *
 * Run daily at 04:00 UTC (configurable). `concurrentExecution = SKIP` prevents overlap.
 *
 * ## Liveness heartbeat (ADR-0237)
 *
 * A deletion sweep is invisible when it stops working: nothing throws, and "no rows deleted" is
 * exactly what a healthy quiet day looks like, so a schedule that silently stopped firing — the
 * `HR000068` class that left five schedulers in this repo never running (#2148, #2187) — reads the
 * same as a clean one. [DomainMetrics.registerWorkflowLiveness] publishes the last-success age so
 * the ADR-0237 staleness rule and `openbank-control-liveness-sentinel` can see it.
 *
 * **The gauge is registered only when the sweep will actually delete** — [enabled] and not
 * [dryRun]. This is deliberate: a heartbeat published by a disabled or preview-only sweep asserts
 * precisely the retention it is not performing, and an operator reading a fresh gauge would
 * conclude session logs are being purged when nothing has been deleted. Absent is the honest
 * signal for "this environment does not run this job", and it is a *different* state from stale —
 * the staleness rule alerts on stale, never on absent. Since [enabled] defaults to `false`, the
 * common case is deliberately no gauge at all until an operator turns the sweep on for real.
 *
 * Registration hangs off [StartupEvent] rather than `@PostConstruct` because `@ApplicationScoped`
 * is lazy: a `@PostConstruct` would first run when the cron first fires, up to a day after boot,
 * leaving the gauge absent for that whole window — and absent is not the same signal as stale.
 * [WorkflowLivenessRecorder.recordSuccess] is called only after the delete actually returned,
 * never on the dry-run or disabled path.
 */
@ApplicationScoped
class SessionLogRetentionScheduler(
    private val sessionLogRepository: SessionLogRepositoryPort,
    private val auditPublisher: AuditEventPublisher,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.retention.session-log.retention-days", defaultValue = "90")
    private val retentionDays: Long,
    @ConfigProperty(name = "openbank.retention.session-log.dry-run", defaultValue = "true")
    private val dryRun: Boolean,
    @ConfigProperty(name = "openbank.retention.session-log.enabled", defaultValue = "false")
    private val enabled: Boolean,
    private val domainMetrics: DomainMetrics,
) {

    private val log = Logger.getLogger(SessionLogRetentionScheduler::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled && !dryRun) {
            liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        }
    }

    @Scheduled(
        cron = "{openbank.retention.session-log.cron:0 0 4 * * ?}",
        concurrentExecution = SKIP,
    )
    suspend fun enforceRetention() {
        if (!enabled) return

        val cutoff = Instant.now(clock).minusSeconds(retentionDays * SECONDS_PER_DAY)

        if (dryRun) {
            val wouldDeleteCount = sessionLogRepository.countOlderThan(cutoff)
            log.infof(
                "[retention] DRY-RUN: would delete %d session log(s) with occurred_at < %s (retention %dd)",
                wouldDeleteCount,
                cutoff,
                retentionDays,
            )
            auditPublisher.publish(
                AuditEvent(
                    actorId = "system",
                    actorType = "SYSTEM",
                    operation = "session-log.retention.dry-run",
                    resourceType = "session_log",
                    resourceId = null,
                    result = AuditResult.SUCCESS,
                    payload = mapOf(
                        "cutoff" to cutoff.toString(),
                        "retentionDays" to retentionDays,
                        "wouldDeleteCount" to wouldDeleteCount,
                    ),
                ),
            )
            return
        }

        val deletedCount = sessionLogRepository.deleteOlderThan(cutoff)
        // Recorded for a zero-row run too: an empty sweep IS a successful sweep, and withholding
        // the heartbeat on a quiet day would make a healthy schedule read as stale.
        liveness?.recordSuccess()
        if (deletedCount > 0) {
            log.infof(
                "[retention] Deleted %d session log(s) with occurred_at < %s (ADR-0118 §5, retention %dd)",
                deletedCount,
                cutoff,
                retentionDays,
            )
        }
        // Emitted even for a zero-row run: DORA Art. 17 wants the job's execution itself
        // reconstructible, not just the rows it happened to touch.
        auditPublisher.publish(
            AuditEvent(
                actorId = "system",
                actorType = "SYSTEM",
                operation = "session-log.retention.enforced",
                resourceType = "session_log",
                resourceId = null,
                result = AuditResult.SUCCESS,
                payload = mapOf(
                    "cutoff" to cutoff.toString(),
                    "retentionDays" to retentionDays,
                    "deletedCount" to deletedCount,
                ),
            ),
        )
    }

    companion object {
        private const val SECONDS_PER_DAY = 86_400L
        private const val WORKFLOW_NAME = "audit-session-log-retention"
        private val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
