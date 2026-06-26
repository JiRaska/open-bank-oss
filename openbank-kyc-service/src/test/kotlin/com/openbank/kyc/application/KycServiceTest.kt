// SPDX-License-Identifier: MPL-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.\n// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.\n
package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.RiskLevel
import com.openbank.kyc.infrastructure.kafka.KycEventPublisher
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class KycServiceTest {

    private val repo = mockk<KycCaseRepository>()
    private val eventPublisher = mockk<KycEventPublisher>()
    private val metrics = mockk<DomainMetrics>(relaxed = true)
    private val clock: Clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var service: KycService

    @BeforeEach
    fun setUp() {
        service = KycService().also {
            it.repo = repo
            it.eventPublisher = eventPublisher
            it.metrics = metrics
            it.clock = clock
        }
    }

    @Test
    fun `open case seeds mandatory checks and publishes open event`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.findActiveByPartyId(partyId) } returns null // no active case → create new
        coEvery { repo.save(any()) } answers { firstArg<KycCase>() }
        every { eventPublisher.publishCaseOpened(any()) } just runs

        val result = service.openCase(partyId)

        assertThat(result.partyId).isEqualTo(partyId)
        assertThat(result.status).isEqualTo(KycCaseStatus.OPEN)
        assertThat(result.riskLevel).isEqualTo(RiskLevel.MEDIUM)
        assertThat(result.checks.map { it.checkType }).containsExactly(
            CheckType.IDENTITY,
            CheckType.ADDRESS,
            CheckType.PEP_SCREENING,
            CheckType.SANCTIONS_SCREENING,
        )
        assertThat(result.checks).allMatch { it.status == CheckStatus.PENDING }
        assertThat(result.expiresAt).isAfter(result.createdAt)

        coVerify { repo.save(match<KycCase> { it.partyId == partyId && it.checks.size == 4 }) }
    }

    @Test
    fun `open case counts a kyc submission`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.findActiveByPartyId(partyId) } returns null
        coEvery { repo.save(any()) } answers { firstArg<KycCase>() }
        every { eventPublisher.publishCaseOpened(any()) } just runs

        service.openCase(partyId)

        verify(exactly = 1) { metrics.kycSubmitted("unknown") }
    }

    @Test
    fun `approve counts an approved verdict`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        val existing = kycCase(id = caseId, checks = emptyList()).copy(status = KycCaseStatus.UNDER_REVIEW)
        coEvery { repo.findById(caseId) } returns existing
        coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }
        every { eventPublisher.publishCaseApproved(any()) } just runs

        service.approve(caseId, "operator-1")

        verify(exactly = 1) { metrics.kycVerdict("unknown", "approved") }
    }

    @Test
    fun `reject counts a rejected verdict`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        val existing = kycCase(id = caseId, checks = emptyList()).copy(status = KycCaseStatus.UNDER_REVIEW)
        coEvery { repo.findById(caseId) } returns existing
        coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }
        every { eventPublisher.publishCaseRejected(any()) } just runs

        service.reject(caseId, "operator-1", "documents invalid")

        verify(exactly = 1) { metrics.kycVerdict("unknown", "rejected") }
    }

    @Test
    fun `openCaseForParty opens a new case when the party has none`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.findActiveByPartyId(partyId) } returns null
        coEvery { repo.save(any()) } answers { firstArg<KycCase>() }
        every { eventPublisher.publishCaseOpened(any()) } just runs

        val result = service.openCaseForParty(partyId)

        assertThat(result.created).isTrue()
        assertThat(result.case.partyId).isEqualTo(partyId)
        assertThat(result.case.status).isEqualTo(KycCaseStatus.OPEN)
        coVerify(exactly = 1) { repo.save(any()) }
        verify(exactly = 1) { eventPublisher.publishCaseOpened(any()) }
    }

    @Test
    fun `openCaseForParty is idempotent - returns existing case without opening a duplicate`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val existing = kycCase(checks = emptyList()).copy(partyId = partyId)
        coEvery { repo.findActiveByPartyId(partyId) } returns existing

        val result = service.openCaseForParty(partyId)

        assertThat(result.created).isFalse()
        assertThat(result.case).isSameAs(existing)
        coVerify(exactly = 0) { repo.save(any()) }
        verify(exactly = 0) { eventPublisher.publishCaseOpened(any()) }
    }

    @Test
    fun `openCaseForParty resolves a concurrent-insert race by returning the winner's case`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val winner = kycCase(checks = emptyList()).copy(partyId = partyId)
        // First lookup sees nothing; our insert loses the uq_kyc_cases_active_party race;
        // the re-read then returns the case the concurrent winner committed.
        coEvery { repo.findActiveByPartyId(partyId) } returns null andThen winner
        coEvery { repo.save(any()) } throws RuntimeException("duplicate key value violates unique constraint")
        every { eventPublisher.publishCaseOpened(any()) } just runs

        val result = service.openCaseForParty(partyId)

        assertThat(result.created).isFalse()
        assertThat(result.case).isSameAs(winner)
        coVerify(exactly = 2) { repo.findActiveByPartyId(partyId) }
    }

    @Test
    fun `openCaseForParty rethrows when the insert fails for a non-race reason`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.findActiveByPartyId(partyId) } returns null // no active case, before and after
        coEvery { repo.save(any()) } throws RuntimeException("connection reset")
        every { eventPublisher.publishCaseOpened(any()) } just runs

        assertThatThrownBy { runBlocking { service.openCaseForParty(partyId) } }
            .hasMessageContaining("connection reset")
    }

    @Test
    fun `update check status moves case to under review when all checks pass`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        val existing = kycCase(
            id = caseId,
            checks = listOf(
                check(caseId, CheckType.IDENTITY, CheckStatus.PENDING),
                check(caseId, CheckType.ADDRESS, CheckStatus.PASSED),
                check(caseId, CheckType.PEP_SCREENING, CheckStatus.PASSED),
                check(caseId, CheckType.SANCTIONS_SCREENING, CheckStatus.PASSED),
            ),
        )
        val updatedChecks: List<KycCheck> = existing.checks.map { check: KycCheck ->
            if (check.checkType == CheckType.IDENTITY) {
                check.copy(
                    status = CheckStatus.PASSED,
                    result = "document verified",
                    performedAt = Instant.parse("2026-05-27T12:00:00Z"),
                )
            } else {
                check
            }
        }
        val expectedUpdated = existing.copy(
            status = KycCaseStatus.UNDER_REVIEW,
            checks = updatedChecks,
            updatedAt = Instant.parse("2026-05-27T12:00:00Z"),
        )

        coEvery { repo.findById(caseId) } returns existing
        coEvery { repo.update(any<KycCase>()) } returns expectedUpdated
        every { eventPublisher.publishCaseStatusChanged(any()) } just runs

        val result = service.updateCheckStatus(caseId, CheckType.IDENTITY, CheckStatus.PASSED, "document verified")

        assertThat(result.status).isEqualTo(KycCaseStatus.UNDER_REVIEW)
        assertThat(result.checks.first { it.checkType == CheckType.IDENTITY }.status).isEqualTo(CheckStatus.PASSED)
        assertThat(result.checks.first { it.checkType == CheckType.IDENTITY }.result).isEqualTo("document verified")
        assertThat(result.checks.first { it.checkType == CheckType.IDENTITY }.performedAt).isNotNull()

        coVerify { repo.update(match<KycCase> { it.status == KycCaseStatus.UNDER_REVIEW }) }
    }

    @Test
    fun `listCases with status filter dispatches to listByStatus`(): Unit = runBlocking {
        val cases = listOf(kycCase(checks = emptyList()), kycCase(checks = emptyList()))
        coEvery { repo.listByStatus(KycCaseStatus.OPEN, 0, 20) } returns cases

        val result = service.listCases(0, 20, KycCaseStatus.OPEN)

        assertThat(result).hasSize(2)
        coVerify(exactly = 1) { repo.listByStatus(KycCaseStatus.OPEN, 0, 20) }
        coVerify(exactly = 0) { repo.listAll(any(), any()) }
    }

    @Test
    fun `listCases without status dispatches to listAll`(): Unit = runBlocking {
        val cases = listOf(kycCase(checks = emptyList()))
        coEvery { repo.listAll(0, 20) } returns cases

        val result = service.listCases(0, 20, null)

        assertThat(result).hasSize(1)
        coVerify(exactly = 1) { repo.listAll(0, 20) }
        coVerify(exactly = 0) { repo.listByStatus(any(), any(), any()) }
    }

    @Test
    fun `countCases with status dispatches to countByStatus`(): Unit = runBlocking {
        coEvery { repo.countByStatus(KycCaseStatus.UNDER_REVIEW) } returns 5L

        val result = service.countCases(KycCaseStatus.UNDER_REVIEW)

        assertThat(result).isEqualTo(5L)
        coVerify(exactly = 1) { repo.countByStatus(KycCaseStatus.UNDER_REVIEW) }
        coVerify(exactly = 0) { repo.countAll() }
    }

    @Test
    fun `countCases without status dispatches to countAll`(): Unit = runBlocking {
        coEvery { repo.countAll() } returns 42L

        val result = service.countCases(null)

        assertThat(result).isEqualTo(42L)
        coVerify(exactly = 1) { repo.countAll() }
        coVerify(exactly = 0) { repo.countByStatus(any()) }
    }

    // ── Idempotency tests (Sprint 1) ─────────────────────────────────────────

    @Test
    fun `openCase rejects a duplicate active case with a conflict pointing at the existing one`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val existingCase = kycCase(checks = listOf(check(UUID.randomUUID(), CheckType.IDENTITY, CheckStatus.PENDING)))
            .copy(partyId = partyId, status = KycCaseStatus.OPEN)
        coEvery { repo.findActiveByPartyId(partyId) } returns existingCase

        assertThatThrownBy { runBlocking { service.openCase(partyId) } }
            .isInstanceOf(KycCaseConflictException::class.java)
            .hasMessageContaining(existingCase.id.toString())

        coVerify(exactly = 0) { repo.save(any()) }
        coVerify(exactly = 0) { eventPublisher.publishCaseOpened(any()) }
    }

    @Test
    fun `openCase creates new case when the party has only a terminal (REJECTED) case`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        // A terminal case is not "active", so findActiveByPartyId returns null and a fresh one opens.
        coEvery { repo.findActiveByPartyId(partyId) } returns null
        coEvery { repo.save(any()) } answers { firstArg<KycCase>() }
        every { eventPublisher.publishCaseOpened(any()) } just runs

        val result = service.openCase(partyId)

        assertThat(result.status).isEqualTo(KycCaseStatus.OPEN)
        coVerify(exactly = 1) { repo.save(any()) }
    }

    // ── approveCase / rejectCase — state machine validation (ADR-0068) ───────

    @Test
    fun `approveCase transitions UNDER_REVIEW to APPROVED and records approver and reason`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        val existing = kycCase(id = caseId, checks = emptyList()).copy(status = KycCaseStatus.UNDER_REVIEW)
        coEvery { repo.findById(caseId) } returns existing
        coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }
        every { eventPublisher.publishCaseApproved(any()) } just runs

        val result = service.approveCase(caseId, "operator-kyc-1", "All documents verified, identity confirmed")

        assertThat(result.status).isEqualTo(KycCaseStatus.APPROVED)
        assertThat(result.reviewedBy).isEqualTo("operator-kyc-1")
        assertThat(result.notes).isEqualTo("All documents verified, identity confirmed")
        assertThat(result.reviewedAt).isNotNull()
        coVerify {
            repo.update(
                match<KycCase> { it.status == KycCaseStatus.APPROVED && it.reviewedBy == "operator-kyc-1" },
            )
        }
        verify(exactly = 1) { eventPublisher.publishCaseApproved(any()) }
        verify(exactly = 1) { metrics.kycVerdict("unknown", "approved") }
    }

    @Test
    fun `approveCase rejects non-UNDER_REVIEW cases with InvalidStateTransitionException`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()

        for (invalidStatus in listOf(
            KycCaseStatus.OPEN,
            KycCaseStatus.DOCUMENTS_REQUIRED,
            KycCaseStatus.APPROVED,
            KycCaseStatus.REJECTED,
            KycCaseStatus.EXPIRED,
        )) {
            val existing = kycCase(id = caseId, checks = emptyList()).copy(status = invalidStatus)
            coEvery { repo.findById(caseId) } returns existing

            assertThatThrownBy {
                runBlocking { service.approveCase(caseId, "operator-1", "some valid reason text") }
            }
                .isInstanceOf(InvalidStateTransitionException::class.java)
                .hasMessageContaining("approve")
                .hasMessageContaining("UNDER_REVIEW")

            coVerify(exactly = 0) { repo.update(any()) }
        }
    }

    @Test
    fun `rejectCase transitions UNDER_REVIEW to REJECTED and records rejector and reason`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        val existing = kycCase(id = caseId, checks = emptyList()).copy(status = KycCaseStatus.UNDER_REVIEW)
        coEvery { repo.findById(caseId) } returns existing
        coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }
        every { eventPublisher.publishCaseRejected(any()) } just runs

        val result = service.rejectCase(caseId, "operator-kyc-2", "Address document expired and not replaceable")

        assertThat(result.status).isEqualTo(KycCaseStatus.REJECTED)
        assertThat(result.reviewedBy).isEqualTo("operator-kyc-2")
        assertThat(result.notes).isEqualTo("Address document expired and not replaceable")
        assertThat(result.reviewedAt).isNotNull()
        coVerify {
            repo.update(
                match<KycCase> { it.status == KycCaseStatus.REJECTED && it.reviewedBy == "operator-kyc-2" },
            )
        }
        verify(exactly = 1) { eventPublisher.publishCaseRejected(any()) }
        verify(exactly = 1) { metrics.kycVerdict("unknown", "rejected") }
    }

    @Test
    fun `rejectCase rejects non-UNDER_REVIEW cases with InvalidStateTransitionException`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()

        for (invalidStatus in listOf(
            KycCaseStatus.OPEN,
            KycCaseStatus.DOCUMENTS_REQUIRED,
            KycCaseStatus.APPROVED,
            KycCaseStatus.REJECTED,
            KycCaseStatus.EXPIRED,
        )) {
            val existing = kycCase(id = caseId, checks = emptyList()).copy(status = invalidStatus)
            coEvery { repo.findById(caseId) } returns existing

            assertThatThrownBy {
                runBlocking { service.rejectCase(caseId, "operator-2", "documents missing completely") }
            }
                .isInstanceOf(InvalidStateTransitionException::class.java)
                .hasMessageContaining("reject")
                .hasMessageContaining("UNDER_REVIEW")

            coVerify(exactly = 0) { repo.update(any()) }
        }
    }

    @Test
    fun `approveCase throws KycCaseNotFoundException when case does not exist`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        coEvery { repo.findById(caseId) } returns null

        assertThatThrownBy { runBlocking { service.approveCase(caseId, "operator-1", "valid reason here") } }
            .isInstanceOf(KycCaseNotFoundException::class.java)
    }

    @Test
    fun `rejectCase throws KycCaseNotFoundException when case does not exist`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        coEvery { repo.findById(caseId) } returns null

        assertThatThrownBy { runBlocking { service.rejectCase(caseId, "operator-1", "valid reason here") } }
            .isInstanceOf(KycCaseNotFoundException::class.java)
    }

    @Test
    fun `approveCase rejects reason shorter than 10 chars with InvalidApprovalReasonException`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()

        assertThatThrownBy { runBlocking { service.approveCase(caseId, "operator-1", "short") } }
            .isInstanceOf(InvalidApprovalReasonException::class.java)
            .hasMessageContaining("10 characters")

        coVerify(exactly = 0) { repo.findById(any()) }
    }

    @Test
    fun `rejectCase rejects reason shorter than 10 chars with InvalidApprovalReasonException`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()

        assertThatThrownBy { runBlocking { service.rejectCase(caseId, "operator-1", "no") } }
            .isInstanceOf(InvalidApprovalReasonException::class.java)
            .hasMessageContaining("10 characters")

        coVerify(exactly = 0) { repo.findById(any()) }
    }

    private fun kycCase(id: UUID = UUID.randomUUID(), checks: List<KycCheck>) = KycCase(
        id = id,
        partyId = UUID.randomUUID(),
        status = KycCaseStatus.OPEN,
        riskLevel = RiskLevel.MEDIUM,
        assignedTo = null,
        checks = checks,
        notes = null,
        reviewedBy = null,
        reviewedAt = null,
        expiresAt = Instant.now(clock).plusSeconds(3600),
        createdAt = Instant.now(clock),
        updatedAt = Instant.now(clock),
    )

    private fun check(caseId: UUID, type: CheckType, status: CheckStatus) = KycCheck(
        id = UUID.randomUUID(),
        caseId = caseId,
        checkType = type,
        status = status,
        result = null,
        provider = null,
        performedAt = null,
        createdAt = Instant.now(clock),
    )
}
