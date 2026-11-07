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
import com.openbank.cardprocessing.application.usecase.RedriveResult
import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.support.capturePostingLogs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        ClearingLedgerPoster(
            ledger,
            postings,
            metrics,
            clock,
            backoffSeconds = 60,
            backoffMaxSeconds = 3600,
            firstGraceSeconds = 60,
        )
    private val redriver =
        LedgerPostingRedriver(
            postings,
            authorizations,
            poster,
            clock,
            maxAttempts = 3,
            batchSize = 10,
            leaseSeconds = 300,
        )

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

    private val token = UUID.randomUUID()

    @Test
    fun `a POSTED outcome is recorded with no next attempt`(): Unit = runBlocking {
        val txId = UUID.randomUUID()
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.POSTED, txId, "COMPLETED")
        val c = clearing()

        poster.post(authorization, c, token)

        coVerify(exactly = 1) {
            postings.recordAttempt(c.id, token, PostingStatus.POSTED, txId, null, now, null)
        }
    }

    @Test
    fun `FAILED and UNKNOWN are recorded with an exponential, capped backoff`(): Unit = runBlocking {
        val next = slot<Instant>()
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.UNKNOWN, null, "unreadable")
        val c = clearing(attempts = 2)

        poster.post(authorization, c, token)

        coVerify {
            postings.recordAttempt(c.id, token, PostingStatus.UNKNOWN, null, "COMPLETION_UNPROVEN", now, capture(next))
        }
        // third attempt: 60s * 2^2
        assertThat(Duration.between(now, next.captured)).isEqualTo(Duration.ofSeconds(240))
        assertThat(poster.backoff(1)).isEqualTo(Duration.ofSeconds(60))
        assertThat(poster.backoff(50)).describedAs("capped").isEqualTo(Duration.ofSeconds(3600))
    }

    @Test
    fun `a disabled adapter counts no attempt and pushes the row out by the backoff ceiling`(): Unit = runBlocking {
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.SKIPPED_DISABLED, null, "off")
        val c = clearing()

        poster.post(authorization, c, token)

        coVerify(exactly = 0) { postings.recordAttempt(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { postings.deferWithoutAttempt(c.id, token, now.plusSeconds(3600)) }
    }

    @Test
    fun `failing to record the outcome does not escape - the posting already happened`(): Unit = runBlocking {
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.POSTED, null, null)
        coEvery { postings.recordAttempt(any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("db down")

        val result = poster.post(authorization, clearing(), token)

        assertThat(result.outcome).isEqualTo(PostingOutcome.POSTED)
    }

    @Test
    fun `an outcome the repository refuses (superseded claim or already POSTED) does not escape`(): Unit = runBlocking {
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.FAILED, null, "late")
        coEvery { postings.recordAttempt(any(), any(), any(), any(), any(), any(), any()) } returns false

        val result = poster.post(authorization, clearing(), token)

        assertThat(result.outcome).isEqualTo(PostingOutcome.FAILED)
        coVerify(exactly = 1) {
            postings.recordAttempt(any(), token, PostingStatus.FAILED, any(), any(), any(), any())
        }
    }

    @Test
    fun `the re-driver posts a claimed row with the SAME idempotency key and skips a lost claim`(): Unit = runBlocking {
        val mine = clearing(attempts = 1, status = PostingStatus.UNKNOWN)
        val taken = clearing(attempts = 1, status = PostingStatus.FAILED)
        coEvery { postings.findDue(now, 3, 10) } returns listOf(taken, mine)
        coEvery { postings.claim(taken.id, any(), any(), 3) } returns null
        coEvery { postings.claim(mine.id, any(), any(), 3) } returns token
        coEvery { authorizations.findById(authorization.id) } returns authorization
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.POSTED, UUID.randomUUID(), "COMPLETED")

        val result = redriver.redriveDue()

        assertThat(result).isEqualTo(RedriveResult(attempted = 1, skippedDisabled = 0))
        coVerify(exactly = 1) { ledger.postClearedSpend(authorization, 10_000, "clr-1") }
        coVerify(exactly = 1) {
            postings.recordAttempt(mine.id, token, PostingStatus.POSTED, any(), any(), any(), null)
        }
    }

    @Test
    fun `a clearing whose authorisation is missing is a COUNTED failed attempt, so it reaches the cap`(): Unit =
        runBlocking {
            val orphan = clearing()
            coEvery { postings.findDue(any(), any(), any()) } returns listOf(orphan)
            coEvery { postings.claim(any(), any(), any(), any()) } returns token
            coEvery { authorizations.findById(any()) } returns null

            redriver.redriveDue()

            coVerify(exactly = 0) { ledger.postClearedSpend(any(), any(), any()) }
            coVerify(exactly = 1) {
                postings.recordAttempt(
                    orphan.id,
                    token,
                    PostingStatus.FAILED,
                    null,
                    "AUTHORISATION_NOT_FOUND",
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `an exception on one row is a counted attempt and the rest of the batch continues`(): Unit = runBlocking {
        val bad = clearing()
        val good = clearing()
        coEvery { postings.findDue(any(), any(), any()) } returns listOf(bad, good)
        coEvery { postings.claim(any(), any(), any(), any()) } returns token
        coEvery { authorizations.findById(bad.authorizationId) } throws IllegalStateException("db blip")
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.POSTED, UUID.randomUUID(), "COMPLETED")
        // bad and good share an authorisation id in this fixture; make only the first call throw.
        var calls = 0
        coEvery { authorizations.findById(any()) } answers { if (calls++ == 0) error("db blip") else authorization }

        val result = redriver.redriveDue()

        assertThat(result.attempted).isEqualTo(2)
        coVerify(exactly = 1) {
            postings.recordAttempt(
                bad.id,
                token,
                PostingStatus.FAILED,
                null,
                "REDRIVE_FAILED",
                any(),
                any(),
            )
        }
        coVerify(exactly = 1) {
            postings.recordAttempt(good.id, token, PostingStatus.POSTED, any(), any(), any(), null)
        }
    }

    @Test
    fun `rows skipped because posting is switched off are counted for one summary log`(): Unit = runBlocking {
        val c = clearing()
        coEvery { postings.findDue(any(), any(), any()) } returns listOf(c)
        coEvery { postings.claim(any(), any(), any(), any()) } returns token
        coEvery { authorizations.findById(any()) } returns authorization
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.SKIPPED_DISABLED, null, "off")

        val result = redriver.redriveDue()

        assertThat(result).isEqualTo(RedriveResult(attempted = 0, skippedDisabled = 1))
    }

    @Test
    fun `raw posting details and keys never become stored errors or log data`() {
        val raw = "synthetic-private-upstream-marker"
        val key = "synthetic-private-clearing-key"
        val logs = capturePostingLogs {
            runBlocking {
                val c = clearing().copy(idempotencyKey = key)
                coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
                    PostingResult(PostingOutcome.FAILED, null, raw)
                poster.post(authorization, c, token)
                coVerify(exactly = 1) {
                    postings.recordAttempt(
                        c.id,
                        token,
                        PostingStatus.FAILED,
                        null,
                        "POSTING_FAILED",
                        now,
                        now.plusSeconds(60),
                    )
                }
                coVerify(exactly = 1) { metrics.ledgerPosting(PostingOutcome.FAILED) }
            }
        }
        assertThat(logs).isNotEmpty()
        assertThat(logs.map { it.text }.joinToString()).doesNotContain(raw, key)
        assertThat(logs.mapNotNull { it.thrown }).isEmpty()
    }

    @Test
    fun `redrive exception messages and causes are neither logged nor stored`() {
        val raw = "synthetic-private-exception-marker"
        val cause = "synthetic-private-cause-marker"
        val c = clearing()
        val logs = capturePostingLogs {
            runBlocking {
                coEvery { postings.findDue(any(), any(), any()) } returns listOf(c)
                coEvery { postings.claim(any(), any(), any(), any()) } returns token
                coEvery { authorizations.findById(any()) } throws
                    IllegalStateException(raw, IllegalArgumentException(cause))
                assertThat(redriver.redriveDue().attempted).isEqualTo(1)
                coVerify(exactly = 1) {
                    postings.recordAttempt(
                        c.id,
                        token,
                        PostingStatus.FAILED,
                        null,
                        "REDRIVE_FAILED",
                        now,
                        now.plusSeconds(60),
                    )
                }
            }
        }
        assertThat(logs).isNotEmpty()
        assertThat(logs.map { it.text }.joinToString()).doesNotContain(raw, cause)
        assertThat(logs.mapNotNull { it.thrown }).isEmpty()
    }

    @Test
    fun `record and defer failures log neither raw messages nor cause chains`() {
        val marker = "synthetic-private-record-message"
        val cause = "synthetic-private-record-cause"
        val failure = IllegalStateException(marker, IllegalArgumentException(cause))
        coEvery { postings.recordAttempt(any(), any(), any(), any(), any(), any(), any()) } throws failure
        coEvery { postings.deferWithoutAttempt(any(), any(), any()) } throws failure
        val logs = capturePostingLogs {
            runBlocking {
                coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
                    PostingResult(PostingOutcome.POSTED, null, null)
                assertThat(poster.post(authorization, clearing(), token).outcome).isEqualTo(PostingOutcome.POSTED)
                coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
                    PostingResult(PostingOutcome.SKIPPED_DISABLED, null, null)
                assertThat(poster.post(authorization, clearing(), token).outcome)
                    .isEqualTo(PostingOutcome.SKIPPED_DISABLED)
            }
        }
        assertThat(logs).hasSize(2)
        assertThat(logs.map { it.text }.joinToString()).doesNotContain(marker, cause)
        assertThat(logs.mapNotNull { it.thrown }).isEmpty()
    }

    @Test
    fun `cancellation aborts a redrive without recording a failed attempt`() {
        val c = clearing()
        val cancellation = CancellationException("cancelled")
        coEvery { postings.findDue(any(), any(), any()) } returns listOf(c)
        coEvery { postings.claim(any(), any(), any(), any()) } returns token
        coEvery { authorizations.findById(any()) } throws cancellation
        assertThat(assertThrows<CancellationException> { runBlocking { redriver.redriveDue() } })
            .isSameAs(cancellation)
        coVerify(exactly = 0) { postings.recordAttempt(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancellation during outcome persistence propagates`() {
        val cancellation = CancellationException("cancelled")
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.POSTED, null, null)
        coEvery { postings.recordAttempt(any(), any(), any(), any(), any(), any(), any()) } throws cancellation
        assertThat(
            assertThrows<CancellationException> {
                runBlocking { poster.post(authorization, clearing(), token) }
            },
        ).isSameAs(cancellation)
    }
}
