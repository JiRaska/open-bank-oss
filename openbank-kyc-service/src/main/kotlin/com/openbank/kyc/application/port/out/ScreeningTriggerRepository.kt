// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application.port.out

import com.openbank.kyc.domain.model.KycEvent
import com.openbank.kyc.domain.model.ScreeningTrigger
import com.openbank.kyc.domain.model.ScreeningTriggerFailure
import java.time.Instant
import java.util.UUID

/**
 * Persistence port for ADR-0256 screening triggers. Implemented by
 * [com.openbank.kyc.infrastructure.persistence.repository.ScreeningTriggerRepositoryImpl].
 *
 * Every mutating method runs in its own transaction; [recordOpenFailed] additionally persists
 * the [KycEvent] in the SAME transaction as the state flip, per the ADR-0256 coordinator
 * contract ("written transactionally with the trigger state").
 */
interface ScreeningTriggerRepository {

    /**
     * Inserts a PENDING trigger unless one already exists for the same (publication, party) —
     * the dedupe key that collapses Kafka at-least-once redelivery and the multi-chunk fan-out
     * onto at most one case per party per publication. Returns true when the row was inserted
     * (i.e. this caller owns the open attempt).
     */
    suspend fun insertIfAbsent(trigger: ScreeningTrigger): Boolean

    /** Marks [trigger] OPENED with the coordinator's case id (201, or 409 already-exists). */
    suspend fun recordOpened(trigger: ScreeningTrigger, coordinatorCaseId: String?, at: Instant)

    /**
     * Flips [trigger] to OPEN_FAILED and writes the KYC_COORDINATOR_OPEN_FAILED outbox row in
     * one transaction — the durable, operator-visible failure record. [nextAttemptAt] schedules
     * the retry job's re-claim; null parks the row (attempts exhausted).
     */
    suspend fun recordOpenFailed(
        trigger: ScreeningTrigger,
        reason: ScreeningTriggerFailure,
        attempts: Int,
        nextAttemptAt: Instant?,
        at: Instant,
    )

    /** Expired PENDING leases and nonterminal OPEN_FAILED retries, capped at [limit]. */
    suspend fun findRetryable(now: Instant, limit: Int): List<ScreeningTrigger>

    /** Atomically claims a due row, returning false when another worker already owns it. */
    suspend fun claim(trigger: ScreeningTrigger, now: Instant, leaseUntil: Instant): Boolean

    /**
     * The re-screening backlog as the table holds it right now: rows still PENDING or
     * OPEN_FAILED (retrying or parked), i.e. list changes that have not yet reached the customer
     * as an opened case. One bounded aggregate; the age is computed by the DATABASE clock from
     * `created_at` (the moment the trigger was enqueued), never from `next_attempt_at`, which a
     * retry pushes forward and would make a perpetually failing row look young.
     */
    suspend fun backlog(): ScreeningTriggerBacklog

    /** The trigger row, whatever its state — test and replay read-back. */
    suspend fun findByTriggerId(triggerId: UUID): ScreeningTrigger?
}

/**
 * [pending] undrained triggers; [oldestAgeSeconds] is `now() - min(created_at)` over them, and
 * `0` only when [pending] is `0` — there is no sentinel for "no rows".
 */
data class ScreeningTriggerBacklog(val pending: Long, val oldestAgeSeconds: Long)
