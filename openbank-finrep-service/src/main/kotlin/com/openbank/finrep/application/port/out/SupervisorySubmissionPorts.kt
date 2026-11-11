// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.port.out

import com.openbank.finrep.domain.model.SubmissionAttempt
import com.openbank.finrep.domain.model.SubmissionKey
import com.openbank.finrep.domain.model.SubmissionState
import com.openbank.finrep.domain.model.SupervisorySubmission
import com.openbank.finrep.domain.model.TransportOutcome
import java.time.Instant
import java.util.UUID

/** The package handed to a transport: identity plus the content hash the attempt is bound to. */
data class SupervisoryReturnPackage(
    val submissionId: UUID,
    val key: SubmissionKey,
    val reportingFrameworkVersion: String,
    val taxonomyVersion: String,
    val packageHash: String,
)

data class TransportResult(val outcome: TransportOutcome, val reference: String? = null, val detail: String? = null)

/**
 * Outbound port to a supervisory authority's submission channel. No live adapter exists in this
 * repository: transmitting needs credentials only the institution can arrange. The default
 * adapter answers [TransportOutcome.DRY_RUN] and sends nothing.
 */
interface SupervisoryReturnTransport {
    /** Stable adapter name recorded on every attempt. */
    val name: String

    suspend fun transmit(pkg: SupervisoryReturnPackage): TransportResult
}

interface SupervisorySubmissionRepository {
    suspend fun findById(id: UUID): SupervisorySubmission?

    suspend fun findByKey(key: SubmissionKey): SupervisorySubmission?

    suspend fun list(templateId: String?): List<SupervisorySubmission>

    /** Upsert of the submission row (application-assigned id). */
    suspend fun save(submission: SupervisorySubmission): SupervisorySubmission

    /**
     * Atomically moves the submission PACKAGED -> SUBMITTING. False when another caller already
     * holds the claim or the return is no longer PACKAGED. This is what prevents a double filing.
     */
    suspend fun claimForSubmission(id: UUID, at: Instant, claimId: UUID): Boolean

    /**
     * Conditional, FENCED transition, in ONE transaction:
     * `UPDATE … WHERE id = ? AND state = [expected] AND claim_id = [claimId]` (and, when
     * [claimedBefore] is set, `AND claimed_at < claimedBefore`). The attempt row is appended only
     * when exactly one row moved; false means another claimant or an operator moved it first.
     */
    suspend fun transition(
        expected: SubmissionState,
        next: SupervisorySubmission,
        attempt: SubmissionAttempt,
        claimId: UUID,
        claimedBefore: Instant? = null,
    ): Boolean

    /**
     * Conditional acknowledgement, in ONE transaction: `UPDATE … WHERE id = ? AND state = 'SUBMITTED'`
     * sets [next]'s state and acknowledgement, and the evidence [attempt] is appended only when
     * exactly one row moved. False means a concurrent acknowledgement settled it first.
     */
    suspend fun acknowledge(next: SupervisorySubmission, attempt: SubmissionAttempt): Boolean

    /**
     * Appends an attempt without any state change (e.g. a lapsed holder's late, unapplied result).
     * Every attempt write — here, in [transition] and in [acknowledge] — ignores the caller's
     * `attemptNo` and assigns `coalesce(max(attempt_no), 0) + 1` inside its own transaction, under a
     * `FOR UPDATE` lock on the submission row, so concurrent writers can never collide on
     * `uq_finrep_submission_attempt_no` and lose evidence.
     */
    suspend fun appendAttempt(attempt: SubmissionAttempt)

    suspend fun attempts(submissionId: UUID): List<SubmissionAttempt>
}
