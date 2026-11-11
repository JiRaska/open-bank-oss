// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.CustomerScreeningService.Companion.isUnscreened
import com.openbank.kyc.application.port.out.ScreeningRetryCandidate
import com.openbank.kyc.application.port.out.UnscreenedCaseStore
import com.openbank.kyc.domain.model.CheckType
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Re-runs the customer screen for KYC cases it could not be obtained for when they opened
 * (issue #15723).
 *
 * The open path leaves such a case with its `SANCTIONS_SCREENING` check PENDING — a missing
 * result must never pass a check — and acknowledges the party event, so without this sweep
 * nothing ever asks again and the case waits for an operator or its expiry.
 *
 * A retry is the open path's own step, [CustomerScreeningService.screenOnOpen], followed by the
 * same [KycService.sandboxAutoApprove]: same request, same idempotency key, same settlement
 * rules. It can therefore do nothing the open path could not have done had the provider answered.
 */
@ApplicationScoped
class UnscreenedCaseRetryService(
    private val store: UnscreenedCaseStore,
    private val screening: CustomerScreeningService,
    private val kycService: KycService,
) {

    private val log = Logger.getLogger(UnscreenedCaseRetryService::class.java)

    /** @property settled cases whose sanctions check left PENDING; [failed] threw unexpectedly. */
    data class Outcome(val attempted: Int, val settled: Int, val failed: Int)

    /**
     * One bounded pass: at most [batchSize] cases whose last attempt is at or before
     * [lastAttemptBefore], least recently attempted first.
     */
    // TooGenericExceptionCaught: one case that cannot be retried must not stop the cases after it.
    @Suppress("TooGenericExceptionCaught")
    suspend fun retryBatch(now: Instant, lastAttemptBefore: Instant, batchSize: Int): Outcome {
        store.releaseClosedCases()
        val candidates = store.findRetryCandidates(lastAttemptBefore, batchSize)
        var settled = 0
        var failed = 0
        for (candidate in candidates) {
            try {
                if (retry(candidate)) settled++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                // The case id only: the retained name is PII and stays out of the log.
                log.errorf(e, "kyc.unscreened-case.retry case=%s FAILED — left for the next pass", candidate.case.id)
                moveToBackOfQueue(candidate, now)
            }
        }
        return Outcome(attempted = candidates.size, settled = settled, failed = failed)
    }

    /** @return true when the sanctions check was settled by this attempt. */
    private suspend fun retry(candidate: ScreeningRetryCandidate): Boolean {
        val case = candidate.case
        val sanctions = case.checks.firstOrNull { it.checkType == CheckType.SANCTIONS_SCREENING }
        if (sanctions == null || !sanctions.isUnscreened()) {
            // Settled by a reviewer or an earlier screen in the meantime: nothing left to retry.
            store.release(case.id)
            return false
        }
        val screened = screening.screenOnOpen(case, candidate.partyName)
        kycService.sandboxAutoApprove(case.id)
        return screened.checks.none { it.checkType == CheckType.SANCTIONS_SCREENING && it.isUnscreened() }
    }

    /**
     * A failed attempt is still an attempt. Without this the failing case would keep the oldest
     * attempt time and head every batch; enough of them would starve the healthy cases behind.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun moveToBackOfQueue(candidate: ScreeningRetryCandidate, now: Instant) {
        try {
            store.retainForRetry(candidate.case.id, candidate.partyName, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.errorf(e, "kyc.unscreened-case.retry case=%s could not be re-queued", candidate.case.id)
        }
    }
}
