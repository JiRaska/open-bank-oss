// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.CaseCoordinatorPort
import com.openbank.kyc.application.port.out.CaseOpenOutcome
import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.application.port.out.ScreeningTriggerRepository
import com.openbank.kyc.domain.model.ScreeningTrigger
import com.openbank.kyc.domain.model.ScreeningTriggerFailure
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ScreeningTriggerServiceTest {

    private val triggerRepository = mockk<ScreeningTriggerRepository>(relaxed = true)
    private val kycCaseRepository = mockk<KycCaseRepository>()
    private val coordinatorPort = mockk<CaseCoordinatorPort>()
    private val fixedClock = Clock.fixed(Instant.parse("2026-02-10T12:00:00Z"), ZoneOffset.UTC)

    private val service = ScreeningTriggerService(
        triggerRepository = triggerRepository,
        kycCaseRepository = kycCaseRepository,
        coordinatorPort = coordinatorPort,
        clock = fixedClock,
    )

    private val change = ScreeningTriggerService.ListChange(
        publicationId = UUID.randomUUID(),
        chunkIndex = 0,
        listType = "EU_CONSOLIDATED",
        changedExternalIds = listOf("ext-1", "ext-2"),
        deactivatedExternalIds = listOf("ext-9"),
    )

    @Test
    fun `a changed chunk opens one coordinator case per candidate party`(): Unit = runBlocking {
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        coEvery { kycCaseRepository.findActivePartyIds(any(), any()) } returns listOf(partyA, partyB)
        coEvery { triggerRepository.insertIfAbsent(any()) } returns true
        coEvery { coordinatorPort.openAmlAlertCase(partyA) } returns CaseOpenOutcome.Opened("case-a")
        coEvery { coordinatorPort.openAmlAlertCase(partyB) } returns CaseOpenOutcome.Opened("case-b")

        val result = service.handleListChange(change, candidateLimit = 500)

        assertThat(result.candidates).isEqualTo(2)
        assertThat(result.opened).isEqualTo(2)
        assertThat(result.failed).isZero()
        coVerify(exactly = 1) { triggerRepository.recordOpened(match { it.partyId == partyA }, "case-a", any()) }
        coVerify(exactly = 1) { triggerRepository.recordOpened(match { it.partyId == partyB }, "case-b", any()) }
        coVerify(exactly = 0) { triggerRepository.recordOpenFailed(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a 409 already-exists is an idempotent success and records no coordinator id`(): Unit = runBlocking {
        val party = UUID.randomUUID()
        coEvery { kycCaseRepository.findActivePartyIds(any(), any()) } returns listOf(party)
        coEvery { triggerRepository.insertIfAbsent(any()) } returns true
        coEvery { coordinatorPort.openAmlAlertCase(party) } returns CaseOpenOutcome.AlreadyExists

        val result = service.handleListChange(change, candidateLimit = 500)

        assertThat(result.opened).isEqualTo(1)
        coVerify(exactly = 1) { triggerRepository.recordOpened(any(), null, any()) }
    }

    @Test
    fun `a retryable coordinator failure is recorded durably and not rethrown`(): Unit = runBlocking {
        val party = UUID.randomUUID()
        coEvery { kycCaseRepository.findActivePartyIds(any(), any()) } returns listOf(party)
        coEvery { triggerRepository.insertIfAbsent(any()) } returns true
        coEvery { coordinatorPort.openAmlAlertCase(party) } returns CaseOpenOutcome.Retryable("HTTP 503")
        var capturedReason: ScreeningTriggerFailure? = null
        var capturedAttempts = 0
        var capturedNext: Instant? = null
        coEvery {
            triggerRepository.recordOpenFailed(any(), any(), any(), any(), any())
        } answers {
            capturedReason = args[1] as ScreeningTriggerFailure
            capturedAttempts = args[2] as Int
            capturedNext = args[3] as? Instant
        }

        val result = service.handleListChange(change, candidateLimit = 500)

        // Not opened, but NOT thrown either: the row + outbox event are the hand-off to the retry job.
        assertThat(result.opened).isZero()
        assertThat(result.failed).isEqualTo(1)
        assertThat(capturedReason).isEqualTo(ScreeningTriggerFailure.COORDINATOR_UNAVAILABLE)
        assertThat(capturedAttempts).isEqualTo(1)
        assertThat(capturedNext).isNotNull().isAfter(fixedClock.instant())
    }

    @Test
    fun `a deterministic rejection is parked immediately with attempts exhausted`(): Unit = runBlocking {
        val party = UUID.randomUUID()
        coEvery { kycCaseRepository.findActivePartyIds(any(), any()) } returns listOf(party)
        coEvery { triggerRepository.insertIfAbsent(any()) } returns true
        coEvery { coordinatorPort.openAmlAlertCase(party) } returns
            CaseOpenOutcome.Rejected(ScreeningTriggerFailure.COORDINATOR_REJECTED, "HTTP 403")

        service.handleListChange(change, candidateLimit = 500)

        coVerify(exactly = 1) {
            triggerRepository.recordOpenFailed(
                any(),
                ScreeningTriggerFailure.COORDINATOR_REJECTED,
                5,
                null,
                any(),
            )
        }
    }

    @Test
    fun `a duplicate (publication, party) trigger never calls the coordinator`(): Unit = runBlocking {
        val party = UUID.randomUUID()
        coEvery { kycCaseRepository.findActivePartyIds(any(), any()) } returns listOf(party)
        // Redelivery / a sibling chunk already owns this (publication, party) row.
        coEvery { triggerRepository.insertIfAbsent(any()) } returns false

        val result = service.handleListChange(change, candidateLimit = 500)

        assertThat(result.duplicates).isEqualTo(1)
        assertThat(result.opened).isZero()
        coVerify(exactly = 0) { coordinatorPort.openAmlAlertCase(any()) }
    }

    @Test
    fun `the retry job re-attempts every row the repository returns as due`(): Unit = runBlocking {
        val now = fixedClock.instant()
        val due = trigger(partyId = UUID.randomUUID(), attempts = 2)
        val alsoDue = trigger(partyId = UUID.randomUUID(), attempts = 1)
            .copy(nextAttemptAt = now.minusSeconds(60))
        // The backoff filter is the repository's job (ScreeningTriggerRepositoryIT); the service
        // re-attempts exactly the rows findRetryable hands it and counts the successes.
        coEvery { triggerRepository.claim(any(), any(), any()) } returns true
        coEvery { triggerRepository.findRetryable(now, 100) } returns listOf(due, alsoDue)
        coEvery { coordinatorPort.openAmlAlertCase(due.partyId) } returns CaseOpenOutcome.Opened("case-retry")
        coEvery { coordinatorPort.openAmlAlertCase(alsoDue.partyId) } returns CaseOpenOutcome.AlreadyExists

        val opened = service.retryFailedTriggers(now, limit = 100)

        assertThat(opened).isEqualTo(2)
        coVerify(exactly = 1) {
            triggerRepository.recordOpened(
                match {
                    it.triggerId == due.triggerId && it.attempts == 3
                },
                "case-retry",
                any(),
            )
        }
        coVerify(exactly = 1) {
            triggerRepository.recordOpened(
                match {
                    it.triggerId == alsoDue.triggerId &&
                        it.attempts == 2
                },
                null,
                any(),
            )
        }
    }

    @Test
    fun `one retry failure does not block the rest of the batch`(): Unit = runBlocking {
        val now = fixedClock.instant()
        val broken = trigger(partyId = UUID.randomUUID(), attempts = 4)
        val fine = trigger(partyId = UUID.randomUUID(), attempts = 1)
        coEvery { triggerRepository.claim(any(), any(), any()) } returns true
        coEvery { triggerRepository.findRetryable(now, 100) } returns listOf(broken, fine)
        coEvery { coordinatorPort.openAmlAlertCase(broken.partyId) } throws RuntimeException("db gone")
        coEvery { coordinatorPort.openAmlAlertCase(fine.partyId) } returns CaseOpenOutcome.AlreadyExists

        val opened = service.retryFailedTriggers(now, limit = 100)

        assertThat(opened).isEqualTo(1)
        coVerify(exactly = 1) {
            triggerRepository.recordOpened(
                match {
                    it.triggerId == fine.triggerId &&
                        it.attempts == 2
                },
                null,
                any(),
            )
        }
    }

    @Test
    fun `candidate page size does not truncate the book beyond five hundred parties`(): Unit = runBlocking {
        val firstPage = (1L..500L).map { UUID(0, it) }
        val lastParty = UUID(0, 501)
        coEvery { kycCaseRepository.findActivePartyIds(500, null) } returns firstPage
        coEvery { kycCaseRepository.findActivePartyIds(500, firstPage.last()) } returns listOf(lastParty)
        coEvery { triggerRepository.insertIfAbsent(any()) } returns true
        coEvery { coordinatorPort.openAmlAlertCase(any()) } returns CaseOpenOutcome.AlreadyExists

        val result = service.handleListChange(change, 500)

        assertThat(result.candidates).isEqualTo(501)
        assertThat(result.opened).isEqualTo(501)
        coVerify(exactly = 1) { coordinatorPort.openAmlAlertCase(lastParty) }
        coVerify(exactly = 1) { kycCaseRepository.findActivePartyIds(500, firstPage.last()) }
    }

    @Test
    fun `crash after final claim parks durable failure without another external call`(): Unit = runBlocking {
        val now = fixedClock.instant()
        val abandoned = trigger(UUID.randomUUID(), 5).copy(
            state = com.openbank.kyc.domain.model.ScreeningTriggerState.PENDING,
            nextAttemptAt = now.minusSeconds(1),
        )
        coEvery { triggerRepository.findRetryable(now, 100) } returns listOf(abandoned)
        coEvery { triggerRepository.claim(any(), any(), any()) } returns true

        assertThat(service.retryFailedTriggers(now, 100)).isZero()

        coVerify(exactly = 0) { coordinatorPort.openAmlAlertCase(any()) }
        coVerify(exactly = 1) {
            triggerRepository.recordOpenFailed(
                match { it.attempts == 6 },
                ScreeningTriggerFailure.COORDINATOR_UNAVAILABLE,
                6,
                null,
                now,
            )
        }
    }

    private fun trigger(partyId: UUID, attempts: Int) = ScreeningTrigger.pending(
        partyId = partyId,
        publicationId = change.publicationId,
        chunkIndex = change.chunkIndex,
        listType = change.listType,
        diff = com.openbank.kyc.domain.model.ScreeningTriggerDiff(
            changedExternalIds = change.changedExternalIds,
            deactivatedExternalIds = change.deactivatedExternalIds,
            changeCount = 3,
        ),
        now = fixedClock.instant(),
    ).let {
        it.copy(
            attempts = attempts,
            state = com.openbank.kyc.domain.model.ScreeningTriggerState.OPEN_FAILED,
            failedReason = ScreeningTriggerFailure.COORDINATOR_UNAVAILABLE,
        )
    }
}
