// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application.port.out

import com.openbank.kyc.domain.model.KycCase
import java.time.Instant
import java.util.UUID

/** A case whose customer screen has not been obtained yet, with the name the retry must screen. */
data class ScreeningRetryCandidate(val case: KycCase, val partyName: String)

/**
 * In-flight cases whose `SANCTIONS_SCREENING` check is still PENDING and was never screened.
 * [oldestOpenedAt] is null exactly when [cases] is 0.
 */
data class UnscreenedBacklog(val cases: Long, val oldestOpenedAt: Instant?)

/**
 * What a retry of the customer screen needs to survive the party event that carried it
 * (issue #15723).
 *
 * The party's legal name is not part of [KycCase] — it is never returned by the API or published
 * in an event — so it is kept here, apart from the aggregate, and only for as long as a retry
 * still needs it. Implemented by [com.openbank.kyc.infrastructure.persistence.KycRepository].
 */
interface UnscreenedCaseStore {

    /** Keep [partyName] for a later retry of [caseId] and record that an attempt was made at [attemptedAt]. */
    suspend fun retainForRetry(caseId: UUID, partyName: String, attemptedAt: Instant)

    /** Forget the retained name of [caseId]: its screen was obtained, or no retry will happen. */
    suspend fun release(caseId: UUID)

    /**
     * Non-terminal, un-erased cases with a retained name whose last attempt is at or before
     * [lastAttemptBefore], least recently attempted first, at most [limit].
     */
    suspend fun findRetryCandidates(lastAttemptBefore: Instant, limit: Int): List<ScreeningRetryCandidate>

    /**
     * The CURRENT state of one candidate, read immediately before it is screened: null when the
     * party was erased, the case closed or the name was released since the batch was read. A
     * batch is read once and worked through over seconds; a GDPR erasure landing in between must
     * stop the name from being sent anywhere.
     */
    suspend fun findRetryCandidate(caseId: UUID): ScreeningRetryCandidate?

    /** Forget the retained name of every case that has closed; returns how many were cleared. */
    suspend fun releaseClosedCases(): Int

    /**
     * Counts from the checks themselves, not from the retained names, so a case the sweep cannot
     * retry (opened before the name was kept, or from an event without one) is still counted.
     */
    suspend fun backlog(): UnscreenedBacklog
}
