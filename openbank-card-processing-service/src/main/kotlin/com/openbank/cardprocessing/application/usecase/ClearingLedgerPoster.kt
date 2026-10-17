// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.application.usecase

import com.openbank.cardprocessing.application.port.out.CardProcessingMetricsPort
import com.openbank.cardprocessing.application.port.out.ClearingPostingRepository
import com.openbank.cardprocessing.application.port.out.LedgerPostingPort
import com.openbank.cardprocessing.application.port.out.PostingOutcome
import com.openbank.cardprocessing.application.port.out.PostingResult
import com.openbank.cardprocessing.application.port.out.PostingStatus
import com.openbank.cardprocessing.application.port.out.RecordedClearing
import com.openbank.cardprocessing.domain.model.CardAuthorization
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * One attempt to post a clearing to the ledger, and the record of how it went (V5, #13228).
 *
 * Used by BOTH the inline attempt right after the clearing commits and the re-driver, so the two
 * cannot disagree about what an outcome means. The ledger idempotency key is derived from the
 * authorisation and the clearing key by the adapter, so every attempt - inline, re-driven, twice -
 * presents the same key and transaction-service books the debit once.
 *
 * Recording is best-effort by construction: this runs after the clearing committed and must never
 * undo it. If the record itself cannot be written the row keeps its previous state (PENDING with an
 * elapsed backoff for a fresh clearing) and the re-driver simply finds it again.
 */
@ApplicationScoped
class ClearingLedgerPoster(
    private val ledger: LedgerPostingPort,
    private val postings: ClearingPostingRepository,
    private val metrics: CardProcessingMetricsPort,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.card-processing.posting-backoff-seconds", defaultValue = "60")
    private val backoffSeconds: Long,
    @ConfigProperty(name = "openbank.card-processing.posting-backoff-max-seconds", defaultValue = "3600")
    private val backoffMaxSeconds: Long,
) {
    private val log = Logger.getLogger(ClearingLedgerPoster::class.java)

    suspend fun post(authorization: CardAuthorization, clearing: RecordedClearing): PostingResult {
        val result = ledger.postClearedSpend(authorization, clearing.amountMinorUnits, clearing.idempotencyKey)
        metrics.ledgerPosting(result.outcome)
        if (result.outcome == PostingOutcome.FAILED || result.outcome == PostingOutcome.UNKNOWN) {
            log.errorf(
                "ledger posting %s for authorization %s (%s) - the clearing is recorded, the books may not be: %s",
                result.outcome,
                authorization.id,
                clearing.idempotencyKey.forLog(),
                result.detail?.forLog(),
            )
        }
        // A switched-off adapter did not attempt anything: no attempt is counted, the row stays due.
        if (result.outcome != PostingOutcome.SKIPPED_DISABLED) record(clearing, result)
        return result
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun record(clearing: RecordedClearing, result: PostingResult) {
        val now = Instant.now(clock)
        val status = when (result.outcome) {
            PostingOutcome.POSTED -> PostingStatus.POSTED
            PostingOutcome.UNKNOWN -> PostingStatus.UNKNOWN
            else -> PostingStatus.FAILED
        }
        val next = if (status == PostingStatus.POSTED) null else now.plus(backoff(clearing.postingAttempts + 1))
        try {
            postings.recordAttempt(clearing.id, status, result.transactionId, result.detail, now, next)
        } catch (e: Exception) {
            // observed-by: the unposted gauge and the re-driver - the row keeps its previous state.
            log.errorf(e, "could not record the %s posting state of clearing %s", status, clearing.id)
        }
    }

    /** Exponential: base * 2^(attempt-1), capped. */
    internal fun backoff(attempt: Int): Duration {
        val exponent = (attempt - 1).coerceIn(0, MAX_DOUBLINGS)
        return Duration.ofSeconds((backoffSeconds shl exponent).coerceAtMost(backoffMaxSeconds))
    }

    private companion object {
        /** Beyond this the shift overflows long before the cap matters. */
        const val MAX_DOUBLINGS = 20
    }
}
