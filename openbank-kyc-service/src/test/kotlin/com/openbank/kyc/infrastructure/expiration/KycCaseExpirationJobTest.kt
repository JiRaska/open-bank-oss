// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.expiration

import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycEvent
import com.openbank.kyc.domain.model.RiskLevel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The sweep exists because its absence had a customer-visible consequence: a case abandoned in
 * OPEN blocked that party from ever opening another (#8548). These tests are written against that
 * consequence and against the two boundaries that keep the sweep safe — it must not touch
 * UNDER_REVIEW, and it must not silently drop the rest of a batch when one case fails.
 */
class KycCaseExpirationJobTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-03T03:00:00Z"), ZoneOffset.UTC)
    private val now: Instant = Instant.now(clock)

    private lateinit var repo: KycCaseRepository
    private lateinit var job: KycCaseExpirationJob

    @BeforeEach
    fun setUp() {
        repo = mockk()
        job = KycCaseExpirationJob(
            repo = repo,
            clock = clock,
            enabled = true,
            batchSize = 500,
            domainMetrics = mockk(relaxed = true),
        )
        coEvery { repo.update(any(), any<KycEvent>()) } answers { firstArg() }
    }

    private fun openCase(expiresAt: Instant?) = KycCase(
        id = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        status = KycCaseStatus.OPEN,
        riskLevel = RiskLevel.MEDIUM,
        assignedTo = null,
        checks = emptyList(),
        notes = null,
        reviewedBy = null,
        reviewedAt = null,
        expiresAt = expiresAt,
        createdAt = now.minusSeconds(31 * 24 * 3600),
        updatedAt = now.minusSeconds(31 * 24 * 3600),
    )

    @Test
    fun `an overdue OPEN case is transitioned to EXPIRED, releasing the party for a new case`() {
        val case = openCase(now.minusSeconds(3600))
        coEvery { repo.findExpirableOpenCases(any(), any()) } returns listOf(case)

        val expired = runBlocking { job.expireBatch(now) }

        assertThat(expired).isEqualTo(1)
        val saved = slot<KycCase>()
        coVerify { repo.update(capture(saved), any<KycEvent>()) }
        assertThat(saved.captured.id).isEqualTo(case.id)
        assertThat(saved.captured.status).isEqualTo(KycCaseStatus.EXPIRED)
        // The whole point: EXPIRED is terminal, so uq_kyc_cases_active_party no longer blocks.
        assertThat(saved.captured.status.isTerminal).isTrue()
    }

    /**
     * The status flip and its outbox event must be one transaction, so the event is the SAME
     * expired case — publishing the pre-transition state would tell onboarding-service the case is
     * still OPEN and the cockpit funnel would never move.
     */
    @Test
    fun `the published event carries the expired state, not the pre-transition one`() {
        val case = openCase(now.minusSeconds(3600))
        coEvery { repo.findExpirableOpenCases(any(), any()) } returns listOf(case)

        runBlocking { job.expireBatch(now) }

        val event = slot<KycEvent>()
        coVerify { repo.update(any(), capture(event)) }
        // onboarding-service reads this exact key to project the funnel stage.
        assertThat(event.captured.envelope["status"]).isEqualTo(KycCaseStatus.EXPIRED)
        assertThat(event.captured.eventType).isEqualTo("KYC_CASE_STATUS_CHANGED")
        assertThat(event.captured.aggregateId).isEqualTo(case.id)
    }

    /**
     * The load-bearing boundary. A case in UNDER_REVIEW is waiting on a four-eyes decision;
     * expiring it from a timer would clear a compliance decision out of a reviewer's queue. The
     * sweep asks the repository for OPEN cases only and must never widen that.
     */
    @Test
    fun `the sweep only ever asks for OPEN cases`() {
        coEvery { repo.findExpirableOpenCases(any(), any()) } returns emptyList()

        runBlocking { job.expireBatch(now) }

        coVerify(exactly = 1) { repo.findExpirableOpenCases(now, 500) }
        coVerify(exactly = 0) { repo.update(any(), any<KycEvent>()) }
    }

    /**
     * The same boundary, checked where a unit test can actually see it. The repository's OPEN
     * filter is a JPQL string no unit test can reach, so the job refuses a non-OPEN case it is
     * handed — otherwise widening that query would break nothing visible.
     */
    @Test
    fun `a case in UNDER_REVIEW is refused even when the repository hands one over`() {
        val underReview = openCase(now.minusSeconds(3600)).copy(status = KycCaseStatus.UNDER_REVIEW)
        val open = openCase(now.minusSeconds(3600))
        coEvery { repo.findExpirableOpenCases(any(), any()) } returns listOf(underReview, open)

        val expired = runBlocking { job.expireBatch(now) }

        assertThat(expired).isEqualTo(1)
        coVerify(exactly = 0) { repo.update(match { it.id == underReview.id }, any<KycEvent>()) }
        coVerify { repo.update(match { it.id == open.id }, any<KycEvent>()) }
    }

    @Test
    fun `one failing case does not abandon the rest of the batch`() {
        val doomed = openCase(now.minusSeconds(7200))
        val fine = openCase(now.minusSeconds(3600))
        coEvery { repo.findExpirableOpenCases(any(), any()) } returns listOf(doomed, fine)
        coEvery { repo.update(match { it.id == doomed.id }, any<KycEvent>()) } throws
            IllegalStateException("db down")

        val expired = runBlocking { job.expireBatch(now) }

        assertThat(expired).isEqualTo(1)
        coVerify { repo.update(match { it.id == fine.id }, any<KycEvent>()) }
    }

    @Test
    fun `a disabled sweep does nothing at all`() {
        val disabled = KycCaseExpirationJob(
            repo = repo,
            clock = clock,
            enabled = false,
            batchSize = 500,
            domainMetrics = mockk(relaxed = true),
        )

        runBlocking { disabled.sweepExpiredCases() }

        coVerify(exactly = 0) { repo.findExpirableOpenCases(any(), any()) }
    }

    @Test
    fun `a sweep failure is swallowed so the cron survives to the next tick`() {
        coEvery { repo.findExpirableOpenCases(any(), any()) } throws IllegalStateException("db down")

        runBlocking { job.sweepExpiredCases() }

        coVerify(exactly = 1) { repo.findExpirableOpenCases(any(), any()) }
    }

    @Test
    fun `the configured batch size bounds one tick`() {
        val small = KycCaseExpirationJob(
            repo = repo,
            clock = clock,
            enabled = true,
            batchSize = 7,
            domainMetrics = mockk(relaxed = true),
        )
        coEvery { repo.findExpirableOpenCases(any(), any()) } returns emptyList()

        runBlocking { small.expireBatch(now) }

        coVerify { repo.findExpirableOpenCases(now, 7) }
    }
}
