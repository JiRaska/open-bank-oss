// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.application.usecase

import com.openbank.cardprocessing.application.port.out.CardAuthorizationRepository
import com.openbank.cardprocessing.application.port.out.ClearingPostingRepository
import com.openbank.cardprocessing.application.port.out.PostingBacklog
import com.openbank.cardprocessing.application.port.out.RecordedClearing
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Posts the clearings whose ledger posting is still owed (V5, #13228): PENDING (the inline attempt
 * never recorded an outcome), FAILED and UNKNOWN.
 *
 * Every attempt presents the SAME ledger idempotency key as the first one (the adapter derives it
 * from the authorisation and the clearing key), and transaction-service dedupes on it - so running
 * a row twice, from two pods, or after a crash that lost the outcome, books the debit once.
 *
 * A row is claimed (a short lease on its next-attempt time) before it is posted, so two instances do
 * not post the same row at once. After [maxAttempts] a row is no longer picked up - it is NOT dropped:
 * it stays FAILED/UNKNOWN and the exhausted count in [backlog] is what the alert watches.
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
) {
    private val log = Logger.getLogger(LedgerPostingRedriver::class.java)

    /** Re-drives one batch; returns how many clearings were attempted. */
    suspend fun redriveDue(): Int {
        val now = Instant.now(clock)
        return postings.findDue(now, maxAttempts, batchSize).count { redrive(it, now) }
    }

    /** `true` when the row was claimed and a posting was attempted. */
    private suspend fun redrive(clearing: RecordedClearing, now: Instant): Boolean {
        if (!postings.claim(clearing.id, now, now.plus(LEASE), maxAttempts)) return false
        val authorization = repository.findById(clearing.authorizationId)
        if (authorization == null) {
            log.errorf("clearing %s has no authorisation %s - cannot re-drive", clearing.id, clearing.authorizationId)
            return false
        }
        poster.post(authorization, clearing)
        return true
    }

    suspend fun backlog(): PostingBacklog = postings.backlog(maxAttempts)

    private companion object {
        /** A claimed row is invisible to other instances for this long if its owner dies mid-attempt. */
        val LEASE: Duration = Duration.ofMinutes(5)
    }
}
