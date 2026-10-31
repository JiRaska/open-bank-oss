// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.CaseCoordinatorPort
import com.openbank.kyc.application.port.out.CaseOpenOutcome
import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.application.port.out.ScreeningTriggerRepository
import com.openbank.kyc.domain.model.ScreeningTrigger
import com.openbank.kyc.domain.model.ScreeningTriggerDiff
import com.openbank.kyc.domain.model.ScreeningTriggerEvents
import com.openbank.kyc.domain.model.ScreeningTriggerFailure
import com.openbank.kyc.domain.model.ScreeningTriggerState
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * ADR-0256 trigger orchestration: turns a `SANCTIONS_LIST_CHANGED` publication chunk into at most
 * one screening case per candidate party, with a durable failure path (contract:
 * docs/design/adr-0256-kyc-case-coordinator-contract.md).
 *
 * Two entry points share [openCaseForTrigger]:
 *
 * - [handleListChange] — the Kafka consumer's path. The PENDING insert (`insertIfAbsent`,
 *   dedupe key (publication, party)) happens BEFORE the coordinator call and commits
 *   independently, so a redelivery resumes instead of duplicating: completed parties find their
 *   row and are skipped, an in-flight party re-attempts its open.
 * - [retryFailedTriggers] — the scheduled worker's path, re-claiming OPEN_FAILED rows whose
 *   backoff elapsed. The coordinator-call failure is NOT rethrown to Kafka here: the row plus
 *   its KYC_COORDINATOR_OPEN_FAILED outbox event are the durable record, and the retry job owns
 *   re-attempt. Only infrastructure failure (the trigger table itself) propagates — the consumer
 *   wraps [handleListChange] in EventRetry and rethrows so the connector dead-letters.
 *
 * Triggers only open cases; they never conclude them (ADR-0256 D2).
 */
@ApplicationScoped
class ScreeningTriggerService(
    private val triggerRepository: ScreeningTriggerRepository,
    private val kycCaseRepository: KycCaseRepository,
    private val coordinatorPort: CaseCoordinatorPort,
    private val clock: Clock,
) {

    private val log = Logger.getLogger(ScreeningTriggerService::class.java)

    /** One chunk of one publication, pre-parsed from the sanctions event payload. */
    data class ListChange(
        val publicationId: UUID,
        val chunkIndex: Long,
        val listType: String?,
        val changedExternalIds: List<String>,
        val deactivatedExternalIds: List<String>,
    )

    /** Outcome of one [handleListChange] call, for logs and tests. */
    data class HandleResult(val candidates: Int, val opened: Int, val failed: Int, val duplicates: Int)

    suspend fun handleListChange(change: ListChange, candidateLimit: Int): HandleResult {
        val diff = ScreeningTriggerDiff(
            changedExternalIds = change.changedExternalIds,
            deactivatedExternalIds = change.deactivatedExternalIds,
            changeCount = change.changedExternalIds.size + change.deactivatedExternalIds.size,
        )
        require(candidateLimit > 0) { "candidate page size must be positive" }
        var afterPartyId: UUID? = null
        var candidateCount = 0
        var opened = 0
        var failed = 0
        var duplicates = 0
        while (true) {
            val candidates = kycCaseRepository.findActivePartyIds(candidateLimit, afterPartyId)
            candidateCount += candidates.size
            for (partyId in candidates) {
                val trigger = ScreeningTrigger.pending(
                    partyId = partyId,
                    publicationId = change.publicationId,
                    chunkIndex = change.chunkIndex,
                    listType = change.listType,
                    diff = diff,
                    now = Instant.now(clock),
                ).copy(attempts = 1, nextAttemptAt = Instant.now(clock) + LEASE)
                // Insert FIRST, durably and idempotently: whatever happens after (coordinator down,
                // pod killed mid-chunk, Kafka redelivery), the (publication, party) row decides
                // whether the open still owes work.
                if (!triggerRepository.insertIfAbsent(trigger)) {
                    duplicates++
                    continue
                }
                if (openCaseForTrigger(trigger, attempts = 1)) opened++ else failed++
            }
            afterPartyId = candidates.lastOrNull()
            if (candidates.size < candidateLimit) break
        }
        return HandleResult(candidateCount, opened, failed, duplicates)
    }

    /**
     * Re-attempts parked opens; returns how many succeeded. One failure never blocks the batch.
     *
     * TooGenericExceptionCaught on the per-row catch: one bad row must not starve the batch; it
     * is re-claimed next tick by definition (still OPEN_FAILED, still due). The row's next tick
     * is the retry, the log line is the signal — mirroring KycCaseExpirationJob.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun retryFailedTriggers(now: Instant, limit: Int): Int {
        val retryable = triggerRepository.findRetryable(now, limit)
        var opened = 0
        for (trigger in retryable) {
            try {
                val leaseUntil = now + LEASE
                if (!triggerRepository.claim(trigger, now, leaseUntil)) continue
                val claimed = trigger.copy(
                    state = ScreeningTriggerState.PENDING,
                    attempts = trigger.attempts + 1,
                    nextAttemptAt = leaseUntil,
                )
                if (claimed.attempts > MAX_ATTEMPTS) {
                    parkOrReschedule(claimed, ScreeningTriggerFailure.COORDINATOR_UNAVAILABLE, claimed.attempts, now)
                } else if (openCaseForTrigger(claimed, attempts = claimed.attempts)) {
                    opened++
                }
            } catch (e: Exception) {
                log.errorf(e, "ADR-0256 retry: trigger %s could not be re-attempted", trigger.triggerId)
            }
        }
        return opened
    }

    /**
     * The single open attempt both entry points use. Returns true when a coordinator case now
     * exists for the party. Coordinator-level failure is recorded durably and returns false —
     * it is never thrown, because the record IS the hand-off to the retry job (see class doc).
     * Only persistence failure propagates.
     */
    private suspend fun openCaseForTrigger(trigger: ScreeningTrigger, attempts: Int): Boolean {
        val now = Instant.now(clock)
        return when (val outcome = coordinatorPort.openAmlAlertCase(trigger.partyId)) {
            is CaseOpenOutcome.Opened -> {
                triggerRepository.recordOpened(trigger, outcome.caseId, now)
                log.infof(
                    "ADR-0256: opened coordinator case %s for party %s (publication %s)",
                    outcome.caseId,
                    trigger.partyId,
                    trigger.publicationId,
                )
                true
            }
            CaseOpenOutcome.AlreadyExists -> {
                triggerRepository.recordOpened(trigger, null, now)
                log.infof(
                    "ADR-0256: coordinator case already runs for party %s (publication %s) — idempotent",
                    trigger.partyId,
                    trigger.publicationId,
                )
                true
            }
            is CaseOpenOutcome.Retryable -> {
                parkOrReschedule(trigger, ScreeningTriggerFailure.COORDINATOR_UNAVAILABLE, attempts, now)
                false
            }
            is CaseOpenOutcome.Rejected -> {
                // A 4xx (or missing config) fails identically every attempt — park immediately.
                parkOrReschedule(trigger, outcome.reason, MAX_ATTEMPTS, now)
                false
            }
        }
    }

    private suspend fun parkOrReschedule(
        trigger: ScreeningTrigger,
        reason: ScreeningTriggerFailure,
        attempts: Int,
        now: Instant,
    ) {
        val exhausted = attempts >= MAX_ATTEMPTS
        val nextAttemptAt = if (exhausted) null else now + BACKOFF.multipliedBy(attempts.toLong())
        // Terminal parking writes the KYC_COORDINATOR_OPEN_FAILED outbox row in the same
        // transaction (ScreeningTriggerRepository.recordOpenFailed) — the operator-visible
        // failure record the ADR-0256 contract requires; logging alone would not comply with D2.
        triggerRepository.recordOpenFailed(trigger, reason, attempts, nextAttemptAt, now)
        if (exhausted) {
            log.errorf(
                "ADR-0256: opening coordinator case for party %s FAILED permanently " +
                    "(%s, %d attempts, publication %s) — %s outbox row written",
                trigger.partyId,
                reason,
                attempts,
                trigger.publicationId,
                ScreeningTriggerEvents.EVENT_COORDINATOR_OPEN_FAILED,
            )
        } else {
            log.warnf(
                "ADR-0256: opening coordinator case for party %s failed (%s, attempt %d/%d) — retry at %s",
                trigger.partyId,
                reason,
                attempts,
                MAX_ATTEMPTS,
                nextAttemptAt,
            )
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        val BACKOFF: Duration = Duration.ofMinutes(5)
        val LEASE: Duration = Duration.ofMinutes(10)
    }
}
