// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.expiration

import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycEvents
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
 * Transitions OPEN KYC cases past their `expires_at` to EXPIRED (issue #8548).
 *
 * **Without this, a party whose case is abandoned can never be KYC'd again.** Every case is created
 * with `expiresAt = now + 30 days`, ADR-0116 describes a time-driven exit to EXPIRED, and
 * `uq_kyc_cases_active_party` treats only APPROVED/REJECTED/EXPIRED as terminal — its own comment
 * says the index is partial precisely "so a party can still be re-KYC'd after its case reaches a
 * terminal state". Nothing ever wrote EXPIRED, so `expires_at` was populated on every row and acted
 * on by nobody: `openCase` returned 409 forever and the database index would have refused the
 * insert regardless. The designed escape hatch was unreachable.
 *
 * **OPEN only, deliberately.** A case in UNDER_REVIEW has its mandatory checks recorded and is
 * waiting on a four-eyes decision; expiring that from a timer would silently clear a compliance
 * decision out of a reviewer's queue. Whether a stalled review should time out is a process choice
 * for a human to make — see #8548 — so this sweep does not make it. That leaves UNDER_REVIEW cases
 * still able to block re-KYC, which is the narrower, correct-by-default residue.
 *
 * **Runs after onboarding-service's abandoned-registration cleaner, and that ordering is load
 * bearing.** That job (02:00 UTC, also 30 days) suspends the abandoned *party*, which moves the
 * cockpit funnel to BLOCKED; it never touched the KYC case. Running at 03:00 lets the two compose:
 * the party is suspended first, then its case is released, so the operator who later reactivates
 * the party from the cockpit (ADR-0068) can actually open a fresh case. Sweeping first would take
 * the record out of that job's `KYC_OPEN` window and the party would stop being suspended at all.
 *
 * **Emits the existing `KYC_CASE_STATUS_CHANGED`, not a new event type.** onboarding-service's
 * consumer reads the status out of that event's payload and projects it; a bespoke
 * `KYC_CASE_EXPIRED` would fall through its `else -> null` and the funnel would never learn the
 * case expired. party-service ignores non-terminal-decision KYC events either way, so the party's
 * own `KycStatus` is untouched — matching the lifecycle's "party stays PENDING_KYC".
 *
 * The status flip and the outbox enqueue share one transaction ([KycCaseRepository.update] with an
 * event), so a case cannot be marked EXPIRED without its event being durably enqueued.
 *
 * The method MUST stay a `suspend fun`. Quarkus invokes a plain `@Scheduled` method on a bare
 * executor thread with no Vert.x context, and every reactive Panache call from it fails with
 * `HR000068` on every firing — the class of defect that left five schedulers in this repo never
 * running (#2148, #2187, #2913).
 */
@ApplicationScoped
class KycCaseExpirationJob(
    private val repo: KycCaseRepository,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.kyc.expiration.enabled", defaultValue = "true")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.kyc.expiration.batch-size", defaultValue = "500")
    private val batchSize: Int,
    private val domainMetrics: DomainMetrics,
) {

    private val log = Logger.getLogger(KycCaseExpirationJob::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    /**
     * Boot-seeded ADR-0237 heartbeat. "No cases to expire" is both the healthy quiet day and what a
     * schedule that stopped firing looks like from outside, so the sweep publishes last-success age
     * rather than relying on a log line nobody reads. A disabled sweep records nothing: it has not
     * proved anything ran.
     */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    // TooGenericExceptionCaught: one bad tick must not kill the cron. Every case still past its
    // deadline is picked up again tomorrow by definition, so a failure costs a day, not a case.
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(
        cron = "{openbank.kyc.expiration.cron:0 0 3 * * ?}",
        identity = "kyc-case-expiration-sweep",
        concurrentExecution = SKIP,
    )
    suspend fun sweepExpiredCases() {
        if (!enabled) return
        val threshold = Instant.now(clock)
        // observed-by: a scheduled sweep, not an event consumer — no message to ack, no DLQ. A
        // failed tick is retried by the next cron firing; errorf is the failure signal.
        try {
            val expired = expireBatch(threshold)
            liveness?.recordSuccess()
            if (expired > 0) {
                log.infof("kyc.case.expiration.sweep expired=%d threshold=%s", expired, threshold)
            }
        } catch (err: Exception) {
            log.errorf(err, "kyc.case.expiration.sweep FAILED threshold=%s", threshold)
        }
    }

    /**
     * Expires one bounded batch, returning how many cases were transitioned.
     *
     * Each case is committed on its own so one failure cannot roll back the rest of the batch; a
     * case that fails here is simply still expirable on the next tick. Internal so the test can
     * drive it without the scheduler.
     */
    internal suspend fun expireBatch(threshold: Instant): Int {
        val due = repo.findExpirableOpenCases(threshold, batchSize)
        var expired = 0
        for (case in due) {
            // Belt and braces over the repository's own OPEN filter, and the only place that filter
            // is checkable by a unit test: the query lives in a JPQL string, so widening it to
            // UNDER_REVIEW would not redden anything without this. A reviewer's case must never be
            // expired by a timer, so the job refuses one even if it is handed one.
            if (case.status != KycCaseStatus.OPEN) {
                log.warnf(
                    "kyc.case.expiration refused case %s in status %s — the sweep expires OPEN only (#8548)",
                    case.id,
                    case.status,
                )
                continue
            }
            @Suppress("TooGenericExceptionCaught")
            // observed-by: this is a scheduled sweep, not an event consumer — there is no message
            // to ack and no DLQ to lose. A case that fails to update here is still OPEN on the next
            // tick's query, so the sweep just picks it up again; the warnf log is the failure signal.
            try {
                val expiredCase = expire(case, threshold)
                repo.update(expiredCase, KycEvents.caseStatusChanged(expiredCase, threshold))
                expired++
            } catch (err: Exception) {
                log.warnf(err, "kyc.case.expiration could not expire case %s — retried next tick", case.id)
            }
        }
        return expired
    }

    private fun expire(case: KycCase, at: Instant): KycCase = case.copy(status = KycCaseStatus.EXPIRED, updatedAt = at)

    private companion object {
        const val WORKFLOW_NAME = "kyc-case-expiration-sweep"
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
