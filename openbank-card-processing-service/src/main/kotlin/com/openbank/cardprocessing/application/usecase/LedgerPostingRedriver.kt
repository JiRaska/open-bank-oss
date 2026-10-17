// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.application.usecase

import com.openbank.cardprocessing.application.port.out.CardAuthorizationRepository
import com.openbank.cardprocessing.application.port.out.ClearingPostingRepository
import com.openbank.cardprocessing.application.port.out.PostingBacklog
import com.openbank.cardprocessing.application.port.out.PostingOutcome
import com.openbank.cardprocessing.application.port.out.RecordedClearing
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant

/** What one re-drive tick did. [skippedDisabled] rows were not attempted: the adapter is switched off. */
data class RedriveResult(val attempted: Int, val skippedDisabled: Int)

/**
 * Posts the clearings whose ledger posting is still owed (V5, #13228): PENDING (the inline attempt
 * never recorded an outcome), FAILED and UNKNOWN.
 *
 * Every attempt presents the SAME ledger idempotency key as the first one (the adapter derives it
 * from the authorisation and the clearing key), and transaction-service dedupes on it. That dedupe is
 * NOT race-safe (#13231), so the real protection is that two attempts never run on one row at once:
 * a row is claimed (a lease on its next-attempt time plus a fresh claim token) before it is posted,
 * and an outcome is recorded only while the row still carries that token and is not POSTED.
 *
 * One bad row never stops the batch: an exception, or an authorisation that cannot be found, is
 * recorded as a counted FAILED attempt, so such a row reaches the ceiling and the alert instead of
 * being re-selected for ever. After [maxAttempts] a row is no longer picked up - it is NOT dropped.
 */
@ApplicationScoped
class LedgerPostingRedriver(
    private val postings: ClearingPostingRepository,
    private val repository: CardAuthorizationRepository,
    private val poster: ClearingLedgerPoster,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.card-processing.posting-max-attempts", defaultValue = "12")
    val maxAttempts: Int,
    @ConfigProperty(name = "openbank.card-processing.posting-redrive-batch", defaultValue = "50")
    private val batchSize: Int,
    @ConfigProperty(name = "openbank.card-processing.posting-lease-seconds", defaultValue = "300")
    private val leaseSeconds: Long,
) {
    private val log = Logger.getLogger(LedgerPostingRedriver::class.java)

    /** Re-drives one batch. */
    suspend fun redriveDue(): RedriveResult {
        val now = Instant.now(clock)
        var attempted = 0
        var skipped = 0
        for (clearing in postings.findDue(now, maxAttempts, batchSize)) {
            when (redrive(clearing, now)) {
                PostingOutcome.SKIPPED_DISABLED -> skipped++
                null -> Unit
                else -> attempted++
            }
        }
        return RedriveResult(attempted, skipped)
    }

    /** The outcome of the attempt, or `null` when the row was not ours to attempt. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun redrive(clearing: RecordedClearing, now: Instant): PostingOutcome? {
        val token = postings.claim(clearing.id, now, now.plusSeconds(leaseSeconds), maxAttempts) ?: return null
        return try {
            val authorization = repository.findById(clearing.authorizationId)
            if (authorization == null) {
                log.errorf("clearing %s has no authorisation %s", clearing.id, clearing.authorizationId)
                poster.recordFailure(clearing, token, "authorisation ${clearing.authorizationId} not found")
                PostingOutcome.FAILED
            } else {
                poster.post(authorization, clearing, token).outcome
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.errorf(e, "re-driving clearing %s failed", clearing.id)
            poster.recordFailure(clearing, token, "re-drive failed: ${e.javaClass.simpleName}: ${e.message}")
            PostingOutcome.FAILED
        }
    }

    suspend fun backlog(): PostingBacklog = postings.backlog(maxAttempts)
}
