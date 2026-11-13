// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.ScreeningRetryCandidate
import com.openbank.kyc.application.port.out.UnscreenedCaseStore
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.RiskLevel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The rules of one retry pass. That the pass actually RUNS — dispatched by the scheduler, on a
 * Vert.x context, against real Postgres — is `UnscreenedCaseRetrySchedulerIT`'s claim, not this
 * class's: a direct call cannot see a scheduler that never fires.
 */
class UnscreenedCaseRetryServiceTest {

    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val cutoff = Instant.parse("2026-11-11T09:58:00Z")
    private val store = mockk<UnscreenedCaseStore>(relaxUnitFun = true)
    private val screening = mockk<CustomerScreeningService>()
    private val kycService = mockk<KycService>()
    private val service = UnscreenedCaseRetryService(store, screening, kycService)

    private fun case(sanctions: CheckStatus = CheckStatus.PENDING, provider: String? = null): KycCase {
        val id = UUID.randomUUID()
        return KycCase(
            id = id, partyId = UUID.randomUUID(), status = KycCaseStatus.OPEN, riskLevel = RiskLevel.MEDIUM,
            assignedTo = null,
            checks = listOf(
                KycCheck(UUID.randomUUID(), id, CheckType.SANCTIONS_SCREENING, sanctions, null, provider, null, now),
            ),
            notes = null, reviewedBy = null, reviewedAt = null, expiresAt = null, createdAt = now, updatedAt = now,
        )
    }

    private fun settled(case: KycCase) =
        case(CheckStatus.PASSED, KycCase.CUSTOMER_SCREENING_PROVIDER).copy(id = case.id)

    /** The batch as read, and — unless a test says otherwise — unchanged when each case is re-read. */
    private fun batch(vararg candidates: ScreeningRetryCandidate) {
        coEvery { store.findRetryCandidates(any(), any()) } returns candidates.toList()
        candidates.forEach { coEvery { store.findRetryCandidate(it.case.id) } returns it }
    }

    init {
        coEvery { store.releaseClosedCases() } returns 0
        coEvery { kycService.sandboxAutoApprove(any()) } answers { case() }
    }

    @Test
    fun `a pass asks the store for at most the batch size and the given attempt cutoff`(): Unit = runBlocking {
        coEvery { store.findRetryCandidates(any(), any()) } returns emptyList()

        val outcome = service.retryBatch(now, cutoff, batchSize = 7)

        coVerify(exactly = 1) { store.findRetryCandidates(cutoff, 7) }
        assertThat(outcome).isEqualTo(UnscreenedCaseRetryService.Outcome(0, 0, 0))
    }

    @Test
    fun `a retry is the open path's screen followed by the sandbox auto-approval`(): Unit = runBlocking {
        val case = case()
        batch(ScreeningRetryCandidate(case, "Jane Clean"))
        coEvery { screening.screenOnOpen(case, "Jane Clean") } returns settled(case)

        val outcome = service.retryBatch(now, cutoff, 10)

        coVerifyOrder {
            screening.screenOnOpen(case, "Jane Clean")
            kycService.sandboxAutoApprove(case.id)
        }
        assertThat(outcome).isEqualTo(UnscreenedCaseRetryService.Outcome(attempted = 1, settled = 1, failed = 0))
    }

    @Test
    fun `a case that is still unscreened after the retry is not counted as settled`(): Unit = runBlocking {
        val case = case()
        batch(ScreeningRetryCandidate(case, "Jane Doe"))
        coEvery { screening.screenOnOpen(case, "Jane Doe") } returns case

        assertThat(service.retryBatch(now, cutoff, 10))
            .isEqualTo(UnscreenedCaseRetryService.Outcome(attempted = 1, settled = 0, failed = 0))
    }

    @Test
    fun `one failing case does not stop the cases after it and goes to the back of the queue`(): Unit = runBlocking {
        val broken = case()
        val healthy = case()
        batch(ScreeningRetryCandidate(broken, "Broken"), ScreeningRetryCandidate(healthy, "Healthy"))
        coEvery { screening.screenOnOpen(broken, "Broken") } throws IllegalStateException("unexpected")
        coEvery { screening.screenOnOpen(healthy, "Healthy") } returns settled(healthy)

        val outcome = service.retryBatch(now, cutoff, 10)

        assertThat(outcome).isEqualTo(UnscreenedCaseRetryService.Outcome(attempted = 2, settled = 1, failed = 1))
        coVerify(exactly = 1) { store.retainForRetry(broken.id, "Broken", now) }
        coVerify(exactly = 1) { kycService.sandboxAutoApprove(healthy.id) }
        coVerify(exactly = 0) { kycService.sandboxAutoApprove(broken.id) }
    }

    @Test
    fun `a check settled in the meantime is not screened again and its name is forgotten`(): Unit = runBlocking {
        val reviewed = case(sanctions = CheckStatus.MANUAL_REVIEW)
        batch(ScreeningRetryCandidate(reviewed, "Jane"))

        val outcome = service.retryBatch(now, cutoff, 10)

        coVerify(exactly = 0) { screening.screenOnOpen(any(), any()) }
        coVerify(exactly = 1) { store.release(reviewed.id) }
        assertThat(outcome.settled).isZero()
    }

    @Test
    fun `names of closed cases are forgotten on every pass`(): Unit = runBlocking {
        coEvery { store.findRetryCandidates(any(), any()) } returns emptyList()

        service.retryBatch(now, cutoff, 10)

        coVerify(exactly = 1) { store.releaseClosedCases() }
    }

    // ── A batch is read once and worked through later (GDPR Art. 17) ──────────

    @Test
    fun `a party erased after the batch was read is not screened and not approved`(): Unit = runBlocking {
        val case = case()
        coEvery { store.findRetryCandidates(any(), any()) } returns listOf(ScreeningRetryCandidate(case, "Jane Erased"))
        // Erased, closed or released in the meantime: the fresh read no longer returns it.
        coEvery { store.findRetryCandidate(case.id) } returns null

        val outcome = service.retryBatch(now, cutoff, 10)

        coVerify(exactly = 0) { screening.screenOnOpen(any(), any()) }
        coVerify(exactly = 0) { kycService.sandboxAutoApprove(any()) }
        coVerify(exactly = 0) { store.retainForRetry(any(), any(), any()) }
        assertThat(outcome).isEqualTo(UnscreenedCaseRetryService.Outcome(attempted = 1, settled = 0, failed = 0))
    }

    @Test
    fun `the screen uses the case and name as they are now, not as the batch read them`(): Unit = runBlocking {
        val stale = case()
        val current = settled(stale)
        coEvery { store.findRetryCandidates(any(), any()) } returns listOf(ScreeningRetryCandidate(stale, "Jane"))
        coEvery { store.findRetryCandidate(stale.id) } returns ScreeningRetryCandidate(current, "Jane")

        service.retryBatch(now, cutoff, 10)

        // Settled since the batch was read: nothing is sent, the name is released.
        coVerify(exactly = 0) { screening.screenOnOpen(any(), any()) }
        coVerify(exactly = 1) { store.release(stale.id) }
    }
}
