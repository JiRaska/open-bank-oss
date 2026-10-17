// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.usecase

import com.openbank.cardprocessing.application.port.out.CardAuthorizationRepository
import com.openbank.cardprocessing.application.port.out.CardProcessingMetricsPort
import com.openbank.cardprocessing.application.port.out.ClearingPostingRepository
import com.openbank.cardprocessing.application.port.out.LedgerPostingPort
import com.openbank.cardprocessing.application.port.out.PostingOutcome
import com.openbank.cardprocessing.application.port.out.PostingResult
import com.openbank.cardprocessing.application.port.out.PostingStatus
import com.openbank.cardprocessing.application.port.out.RecordedClearing
import com.openbank.cardprocessing.application.usecase.ClearingLedgerPoster
import com.openbank.cardprocessing.application.usecase.LedgerPostingRedriver
import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The posting state machine around the ledger call (V5, #13228): what an outcome is recorded as, the
 * backoff, and what the re-driver does with a claimed, lost or orphaned row. The SQL itself and the
 * real cron are covered by `LedgerPostingRedriveIT`.
 */
class LedgerPostingRedriverTest {

    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val ledger = mockk<LedgerPostingPort>()
    private val postings = mockk<ClearingPostingRepository>(relaxed = true)
    private val authorizations = mockk<CardAuthorizationRepository>()
    private val metrics = mockk<CardProcessingMetricsPort>(relaxed = true)
    private val poster =
        ClearingLedgerPoster(ledger, postings, metrics, clock, backoffSeconds = 60, backoffMaxSeconds = 3600)
    private val redriver =
        LedgerPostingRedriver(postings, authorizations, poster, clock, maxAttempts = 3, batchSize = 10)

    private val authorization = CardAuthorization(
        id = UUID.randomUUID(),
        cardId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        amountMinorUnits = 10_000,
        currencyCode = "CZK",
        channel = PresentmentChannel.ONLINE,
        mcc = "5411",
        merchantName = "Potraviny",
        merchantCountry = "CZ",
        status = AuthorizationStatus.CLEARED,
        category = "GROCERIES",
        declineReason = null,
        clearedAmountMinorUnits = 10_000,
        networkReference = "acq-1",
        authorizedAt = now,
        expiresAt = now.plusSeconds(3600),
        updatedAt = now,
    )

    private fun clearing(attempts: Int = 0, status: PostingStatus = PostingStatus.PENDING) = RecordedClearing(
        id = UUID.randomUUID(),
        authorizationId = authorization.id,
        idempotencyKey = "clr-1",
        requestFingerprint = "fp",
        amountMinorUnits = 10_000,
        currencyCode = "CZK",
        appliedAt = now,
        postingStatus = status,
        postingAttempts = attempts,
    )

    @Test
    fun `a POSTED outcome is recorded with no next attempt`(): Unit = runBlocking {
        val txId = UUID.randomUUID()
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.POSTED, txId, "COMPLETED")
        val c = clearing()

        poster.post(authorization, c)

        coVerify(exactly = 1) { postings.recordAttempt(c.id, PostingStatus.POSTED, txId, "COMPLETED", now, null) }
    }

    @Test
    fun `FAILED and UNKNOWN are recorded with an exponential, capped backoff`(): Unit = runBlocking {
        val next = slot<Instant>()
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.UNKNOWN, null, "unreadable")
        val c = clearing(attempts = 2)

        poster.post(authorization, c)

        coVerify { postings.recordAttempt(c.id, PostingStatus.UNKNOWN, null, "unreadable", now, capture(next)) }
        // third attempt: 60s * 2^2
        assertThat(Duration.between(now, next.captured)).isEqualTo(Duration.ofSeconds(240))
        assertThat(poster.backoff(1)).isEqualTo(Duration.ofSeconds(60))
        assertThat(poster.backoff(50)).describedAs("capped").isEqualTo(Duration.ofSeconds(3600))
    }

    @Test
    fun `a disabled adapter counts no attempt - the row stays due`(): Unit = runBlocking {
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.SKIPPED_DISABLED, null, "off")

        poster.post(authorization, clearing())

        coVerify(exactly = 0) { postings.recordAttempt(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `failing to record the outcome does not escape - the posting already happened`(): Unit = runBlocking {
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.POSTED, null, null)
        coEvery { postings.recordAttempt(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("db down")

        val result = poster.post(authorization, clearing())

        assertThat(result.outcome).isEqualTo(PostingOutcome.POSTED)
    }

    @Test
    fun `the re-driver posts a claimed row with the SAME idempotency key and skips a lost claim`(): Unit = runBlocking {
        val mine = clearing(attempts = 1, status = PostingStatus.UNKNOWN)
        val taken = clearing(attempts = 1, status = PostingStatus.FAILED)
        coEvery { postings.findDue(now, 3, 10) } returns listOf(taken, mine)
        coEvery { postings.claim(taken.id, any(), any(), 3) } returns false
        coEvery { postings.claim(mine.id, any(), any(), 3) } returns true
        coEvery { authorizations.findById(authorization.id) } returns authorization
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.POSTED, UUID.randomUUID(), "COMPLETED")

        val attempted = redriver.redriveDue()

        assertThat(attempted).isEqualTo(1)
        coVerify(exactly = 1) { ledger.postClearedSpend(authorization, 10_000, "clr-1") }
        coVerify(exactly = 1) { postings.recordAttempt(mine.id, PostingStatus.POSTED, any(), any(), any(), null) }
    }

    @Test
    fun `a clearing whose authorisation is gone is skipped, not retried into the ledger`(): Unit = runBlocking {
        val orphan = clearing()
        coEvery { postings.findDue(any(), any(), any()) } returns listOf(orphan)
        coEvery { postings.claim(any(), any(), any(), any()) } returns true
        coEvery { authorizations.findById(any()) } returns null

        assertThat(redriver.redriveDue()).isZero()
        coVerify(exactly = 0) { ledger.postClearedSpend(any(), any(), any()) }
    }
}
