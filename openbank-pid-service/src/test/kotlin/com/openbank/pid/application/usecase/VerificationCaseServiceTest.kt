// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.usecase

import com.openbank.pid.application.port.`in`.DecideCaseCommand
import com.openbank.pid.application.port.`in`.OpenCaseCommand
import com.openbank.pid.application.port.`in`.ReopenCaseCommand
import com.openbank.pid.application.port.out.PartyEventPublisher
import com.openbank.pid.application.port.out.VerificationCaseRepository
import com.openbank.pid.domain.event.VerificationCaseDecidedEvent
import com.openbank.pid.domain.event.VerificationCaseOpenedEvent
import com.openbank.pid.domain.model.ApplicantSnapshot
import com.openbank.pid.domain.model.CaseVerdict
import com.openbank.pid.domain.model.IllegalCaseTransition
import com.openbank.pid.domain.model.VerificationCase
import com.openbank.pid.domain.model.VerificationCaseStatus
import com.openbank.pid.domain.model.VerificationTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class VerificationCaseServiceTest {

    private lateinit var repo: VerificationCaseRepository
    private lateinit var events: PartyEventPublisher
    private lateinit var svc: VerificationCaseService

    private val candidate: UUID = UUID.randomUUID()
    private val now: Instant = Instant.parse("2026-06-20T10:00:00Z")

    @BeforeEach
    fun setUp() {
        repo = mockk(relaxed = false)
        events = mockk(relaxed = true)
        svc = VerificationCaseService(repo, events, Clock.fixed(now, ZoneOffset.UTC))
    }

    private fun openCmd() = OpenCaseCommand(
        dedupKey = "RN:abc",
        trigger = VerificationTrigger.RN_COLLISION,
        applicant = ApplicantSnapshot("Jan", "Novak", LocalDate.of(1976, 5, 6), null, listOf("CZ")),
        blindIndex = "abc",
        candidatePartyIds = listOf(candidate),
    )

    private fun storedCase(status: VerificationCaseStatus): VerificationCase = VerificationCase(
        id = UUID.randomUUID(), dedupKey = "RN:abc", trigger = VerificationTrigger.RN_COLLISION,
        status = status,
        applicant = ApplicantSnapshot("Jan", "Novak", LocalDate.of(1976, 5, 6), null, listOf("CZ")),
        blindIndex = "abc", candidatePartyIds = listOf(candidate),
        firstApprover = null, firstVerdict = null, firstLinkPartyId = null, firstNotes = null, firstAt = null,
        secondApprover = null, secondAt = null, finalVerdict = null, finalLinkPartyId = null, decidedAt = null,
        createdAt = now, updatedAt = now,
    )

    @Test
    fun `openOrReuse reuses the existing active case instead of opening a duplicate`(): Unit = runBlocking {
        val existing = storedCase(VerificationCaseStatus.OPEN)
        coEvery { repo.findActiveByDedupKey("RN:abc") } returns existing

        val id = svc.openOrReuse(openCmd())

        assertThat(id).isEqualTo(existing.id)
        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `openOrReuse opens and emits an opened event when none active`(): Unit = runBlocking {
        coEvery { repo.findActiveByDedupKey("RN:abc") } returns null
        coEvery { repo.save(any()) } answers { firstArg() }

        val id = svc.openOrReuse(openCmd())

        assertThat(id).isNotNull()
        coVerify(exactly = 1) { repo.save(any()) }
        coVerify(exactly = 1) { events.publish(any<VerificationCaseOpenedEvent>()) }
    }

    @Test
    fun `priorDecision maps a decided case to its verdict`(): Unit = runBlocking {
        val decided = storedCase(VerificationCaseStatus.DECIDED).copy(
            finalVerdict = CaseVerdict.LINK_TO_EXISTING,
            finalLinkPartyId = candidate,
            decidedAt = now,
        )
        coEvery { repo.findLatestDecidedByDedupKey("RN:abc") } returns decided

        val prior = svc.priorDecision("RN:abc")

        assertThat(prior).isNotNull
        assertThat(prior!!.verdict).isEqualTo(CaseVerdict.LINK_TO_EXISTING)
        assertThat(prior.linkPartyId).isEqualTo(candidate)
    }

    @Test
    fun `decide by two distinct approvers reaches DECIDED and emits a decided event`(): Unit = runBlocking {
        val open = storedCase(VerificationCaseStatus.OPEN)
        coEvery { repo.findById(open.id) } returns open
        coEvery { repo.update(any()) } answers { firstArg() }

        // first vote
        val afterFirst = svc.decide(DecideCaseCommand(open.id, "alice", CaseVerdict.DISTINCT_NEW, null, null))
        assertThat(afterFirst.status).isEqualTo(VerificationCaseStatus.AWAITING_SECOND_APPROVAL)

        // second distinct concurring vote — must read the AWAITING state back
        coEvery { repo.findById(open.id) } returns afterFirst
        val decidedSlot = slot<VerificationCaseDecidedEvent>()
        val afterSecond = svc.decide(DecideCaseCommand(open.id, "bob", CaseVerdict.DISTINCT_NEW, null, null))

        assertThat(afterSecond.status).isEqualTo(VerificationCaseStatus.DECIDED)
        coVerify(exactly = 1) { events.publish(capture(decidedSlot)) }
        assertThat(decidedSlot.captured.firstApprover).isEqualTo("alice")
        assertThat(decidedSlot.captured.secondApprover).isEqualTo("bob")
    }

    @Test
    fun `decide throws VerificationCaseNotFoundException for an unknown case`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns null

        assertThatThrownBy {
            runBlocking { svc.decide(DecideCaseCommand(id, "alice", CaseVerdict.DISTINCT_NEW, null, null)) }
        }.isInstanceOf(VerificationCaseNotFoundException::class.java)
    }

    @Test
    fun `decide on an already-DECIDED case throws IllegalCaseTransition`(): Unit = runBlocking {
        val decided = storedCase(VerificationCaseStatus.DECIDED)
        coEvery { repo.findById(decided.id) } returns decided

        assertThatThrownBy {
            runBlocking { svc.decide(DecideCaseCommand(decided.id, "alice", CaseVerdict.DISTINCT_NEW, null, null)) }
        }.isInstanceOf(IllegalCaseTransition::class.java)
        coVerify(exactly = 0) { repo.update(any()) }
    }

    @Test
    fun `reopen resets an AWAITING case back to OPEN`(): Unit = runBlocking {
        val awaiting = storedCase(VerificationCaseStatus.AWAITING_SECOND_APPROVAL).copy(
            firstApprover = "alice",
            firstVerdict = CaseVerdict.DISTINCT_NEW,
        )
        coEvery { repo.findById(awaiting.id) } returns awaiting
        coEvery { repo.update(any()) } answers { firstArg() }

        val result = svc.reopen(ReopenCaseCommand(awaiting.id, "supervisor"))

        assertThat(result.status).isEqualTo(VerificationCaseStatus.OPEN)
        assertThat(result.firstApprover).isNull()
        coVerify(exactly = 1) { repo.update(any()) }
    }

    @Test
    fun `reopen throws VerificationCaseNotFoundException for an unknown case`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns null

        assertThatThrownBy {
            runBlocking { svc.reopen(ReopenCaseCommand(id, "supervisor")) }
        }.isInstanceOf(VerificationCaseNotFoundException::class.java)
    }

    @Test
    fun `listActive delegates to the repository with OPEN and AWAITING_SECOND_APPROVAL statuses`(): Unit =
        runBlocking {
            val expected = listOf(storedCase(VerificationCaseStatus.OPEN))
            val activeStatuses = listOf(VerificationCaseStatus.OPEN, VerificationCaseStatus.AWAITING_SECOND_APPROVAL)
            coEvery { repo.listByStatuses(activeStatuses) } returns expected

            val result = svc.listActive()

            assertThat(result).isEqualTo(expected)
        }

    @Test
    fun `get delegates to the repository by id`(): Unit = runBlocking {
        val case = storedCase(VerificationCaseStatus.OPEN)
        coEvery { repo.findById(case.id) } returns case

        assertThat(svc.get(case.id)).isEqualTo(case)
    }
}
