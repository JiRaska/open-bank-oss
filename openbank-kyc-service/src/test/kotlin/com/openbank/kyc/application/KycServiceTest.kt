// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.application.port.out.PepScreeningStatus
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.KycEvent
import com.openbank.kyc.domain.model.RiskLevel
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
    private val metrics = mockk<DomainMetrics>(relaxed = true)
    private val clock: Clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var service: KycService

    @BeforeEach
    fun setUp() {
        service = KycService().also {
            it.repo = repo
            it.metrics = metrics
            it.clock = clock
        }
    }

    @Test
    fun `open case seeds mandatory checks and publishes open event`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.findActiveByPartyId(partyId) } returns null // no active case → create new
        coEvery { repo.save(any(), any()) } answers { firstArg<KycCase>() }

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

        coVerify { repo.save(match<KycCase> { it.partyId == partyId && it.checks.size == 4 }, any()) }
    }

    @Test
    fun `open case counts a kyc submission`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.findActiveByPartyId(partyId) } returns null
        coEvery { repo.save(any(), any()) } answers { firstArg<KycCase>() }

        service.openCase(partyId)

        verify(exactly = 1) { metrics.kycSubmitted("individual") }
    }

    @Test
    fun `approve counts an approved verdict`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        val existing = kycCase(id = caseId, checks = emptyList()).copy(status = KycCaseStatus.UNDER_REVIEW)
        coEvery { repo.findById(caseId) } returns existing
        coEvery { repo.update(any<KycCase>(), any()) } answers { firstArg<KycCase>() }
        // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
        coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }

        service.approve(caseId, "operator-1")

        verify(exactly = 1) { metrics.kycVerdict("individual", "approved") }
    }

    @Test
    fun `reject counts a rejected verdict`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        val existing = kycCase(id = caseId, checks = emptyList()).copy(status = KycCaseStatus.UNDER_REVIEW)
        coEvery { repo.findById(caseId) } returns existing
        coEvery { repo.update(any<KycCase>(), any()) } answers { firstArg<KycCase>() }
        // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
        coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }

        service.reject(caseId, "operator-1", "documents invalid")

        verify(exactly = 1) { metrics.kycVerdict("individual", "rejected") }
    }

    @Test
    fun `openCaseForParty opens a new case when the party has none`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.findActiveByPartyId(partyId) } returns null
        coEvery { repo.save(any(), any()) } answers { firstArg<KycCase>() }

        val result = service.openCaseForParty(partyId)

        assertThat(result.created).isTrue()
        assertThat(result.case.partyId).isEqualTo(partyId)
        assertThat(result.case.status).isEqualTo(KycCaseStatus.OPEN)
        coVerify(exactly = 1) { repo.save(any(), any()) }
        coVerify(exactly = 1) { repo.save(any(), match<KycEvent> { it.eventType == "KYC_CASE_OPENED" }) }
    }

    @Test
    fun `openCaseForParty is idempotent - returns existing case without opening a duplicate`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val existing = kycCase(checks = emptyList()).copy(partyId = partyId)
        coEvery { repo.findActiveByPartyId(partyId) } returns existing

        val result = service.openCaseForParty(partyId)

        assertThat(result.created).isFalse()
        assertThat(result.case).isSameAs(existing)
        coVerify(exactly = 0) { repo.save(any(), any()) }
        coVerify(exactly = 0) { repo.save(any(), any()) }
    }

    @Test
    fun `openCaseForParty resolves a concurrent-insert race by returning the winner's case`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val winner = kycCase(checks = emptyList()).copy(partyId = partyId)
        // First lookup sees nothing; our insert loses the uq_kyc_cases_active_party race;
        // the re-read then returns the case the concurrent winner committed.
        coEvery { repo.findActiveByPartyId(partyId) } returns null andThen winner
        coEvery { repo.save(any(), any()) } throws RuntimeException("duplicate key value violates unique constraint")

        val result = service.openCaseForParty(partyId)

        assertThat(result.created).isFalse()
        assertThat(result.case).isSameAs(winner)
        coVerify(exactly = 2) { repo.findActiveByPartyId(partyId) }
    }

    @Test
    fun `openCaseForParty rethrows when the insert fails for a non-race reason`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.findActiveByPartyId(partyId) } returns null // no active case, before and after
        coEvery { repo.save(any(), any()) } throws RuntimeException("connection reset")

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
        coEvery { repo.update(any<KycCase>(), any()) } returns expectedUpdated

        val result = service.updateCheckStatus(caseId, CheckType.IDENTITY, CheckStatus.PASSED, "document verified")

        assertThat(result.status).isEqualTo(KycCaseStatus.UNDER_REVIEW)
        assertThat(result.checks.first { it.checkType == CheckType.IDENTITY }.status).isEqualTo(CheckStatus.PASSED)
        assertThat(result.checks.first { it.checkType == CheckType.IDENTITY }.result).isEqualTo("document verified")
        assertThat(result.checks.first { it.checkType == CheckType.IDENTITY }.performedAt).isNotNull()

        coVerify { repo.update(match<KycCase> { it.status == KycCaseStatus.UNDER_REVIEW }, any()) }
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

        coVerify(exactly = 0) { repo.save(any(), any()) }
        coVerify(exactly = 0) { repo.save(any(), any()) }
    }

    @Test
    fun `openCase creates new case when the party has only a terminal (REJECTED) case`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        // A terminal case is not "active", so findActiveByPartyId returns null and a fresh one opens.
        coEvery { repo.findActiveByPartyId(partyId) } returns null
        coEvery { repo.save(any(), any()) } answers { firstArg<KycCase>() }

        val result = service.openCase(partyId)

        assertThat(result.status).isEqualTo(KycCaseStatus.OPEN)
        coVerify(exactly = 1) { repo.save(any(), any()) }
    }

    // ── approveCase / rejectCase — state machine validation (ADR-0068) ───────

    @Test
    fun `approveCase transitions UNDER_REVIEW to APPROVED and records approver and reason`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        val existing = kycCase(id = caseId, checks = emptyList()).copy(status = KycCaseStatus.UNDER_REVIEW)
        coEvery { repo.findById(caseId) } returns existing
        coEvery { repo.update(any<KycCase>(), any()) } answers { firstArg<KycCase>() }
        // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
        coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }

        val result = service.approveCase(caseId, "operator-kyc-1", "All documents verified, identity confirmed")

        assertThat(result.status).isEqualTo(KycCaseStatus.APPROVED)
        assertThat(result.reviewedBy).isEqualTo("operator-kyc-1")
        assertThat(result.notes).isEqualTo("All documents verified, identity confirmed")
        assertThat(result.reviewedAt).isNotNull()
        coVerify {
            repo.update(
                match<KycCase> { it.status == KycCaseStatus.APPROVED && it.reviewedBy == "operator-kyc-1" },
                any(),
            )
        }
        coVerify(exactly = 1) { repo.update(any<KycCase>(), match<KycEvent> { it.eventType == "KYC_CASE_APPROVED" }) }
        verify(exactly = 1) { metrics.kycVerdict("individual", "approved") }
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

            coVerify(exactly = 0) { repo.update(any(), any()) }
        }
    }

    @Test
    fun `rejectCase transitions UNDER_REVIEW to REJECTED and records rejector and reason`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        val existing = kycCase(id = caseId, checks = emptyList()).copy(status = KycCaseStatus.UNDER_REVIEW)
        coEvery { repo.findById(caseId) } returns existing
        coEvery { repo.update(any<KycCase>(), any()) } answers { firstArg<KycCase>() }
        // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
        coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }

        val result = service.rejectCase(caseId, "operator-kyc-2", "Address document expired and not replaceable")

        assertThat(result.status).isEqualTo(KycCaseStatus.REJECTED)
        assertThat(result.reviewedBy).isEqualTo("operator-kyc-2")
        assertThat(result.notes).isEqualTo("Address document expired and not replaceable")
        assertThat(result.reviewedAt).isNotNull()
        coVerify {
            repo.update(
                match<KycCase> { it.status == KycCaseStatus.REJECTED && it.reviewedBy == "operator-kyc-2" },
                any(),
            )
        }
        coVerify(exactly = 1) { repo.update(any<KycCase>(), match<KycEvent> { it.eventType == "KYC_CASE_REJECTED" }) }
        verify(exactly = 1) { metrics.kycVerdict("individual", "rejected") }
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

            coVerify(exactly = 0) { repo.update(any(), any()) }
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

    // ── getCaseByParty — GDPR Art. 15 export contribution (ADR-0118 §6, issue #268) ─────
    // party-service's GdprAggregationAdapter calls GET /api/v1/kyc/cases/party/{partyId},
    // backed by this pass-through. Covered here because it is the sole PII-exposure path
    // this service contributes to the cross-service subject-access export.

    @Test
    fun `getCaseByParty returns the party's case including check history for GDPR export`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val existing = kycCase(checks = listOf(check(UUID.randomUUID(), CheckType.IDENTITY, CheckStatus.PASSED)))
            .copy(partyId = partyId)
        coEvery { repo.findByPartyId(partyId) } returns existing

        val result = service.getCaseByParty(partyId)

        assertThat(result).isNotNull
        assertThat(result!!.partyId).isEqualTo(partyId)
        assertThat(result.checks).hasSize(1)
        assertThat(result.checks.single().checkType).isEqualTo(CheckType.IDENTITY)
        coVerify(exactly = 1) { repo.findByPartyId(partyId) }
    }

    @Test
    fun `getCaseByParty returns null when the party has no KYC case (no data to export)`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.findByPartyId(partyId) } returns null

        val result = service.getCaseByParty(partyId)

        assertThat(result).isNull()
    }

    // ── applyPepScreeningResult — first-increment PEP check (ADR-0116 delivery note) ────
    // Screens against openbank-sanctions-service's OpenSanctions-derived PEP_GLOBAL list only;
    // not a paid commercial vendor feed, not identity-document verification, not continuous
    // real-time monitoring (see PepScreeningService / SanctionsScreeningAdapter kdoc).

    @Test
    fun `applyPepScreeningResult PASSES the check and leaves risk level unchanged on a clear screen`(): Unit =
        runBlocking {
            val caseId = UUID.randomUUID()
            val existing = kycCase(
                id = caseId,
                checks = listOf(check(caseId, CheckType.PEP_SCREENING, CheckStatus.PENDING)),
            )
            coEvery { repo.findById(caseId) } returns existing
            coEvery { repo.update(any<KycCase>(), any()) } answers { firstArg<KycCase>() }
            // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
            coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }
            // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
            coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }

            val result = service.applyPepScreeningResult(caseId, PepScreeningStatus.CLEAR, 0.1, null)

            val pepCheck = result.checks.single { it.checkType == CheckType.PEP_SCREENING }
            assertThat(pepCheck.status).isEqualTo(CheckStatus.PASSED)
            assertThat(pepCheck.provider).isEqualTo("openbank-sanctions-service:PEP_GLOBAL")
            assertThat(result.riskLevel).isEqualTo(RiskLevel.MEDIUM)
        }

    @Test
    fun `applyPepScreeningResult escalates risk to HIGH and routes to MANUAL_REVIEW on a known-PEP match`(): Unit =
        runBlocking {
            val caseId = UUID.randomUUID()
            val existing = kycCase(
                id = caseId,
                checks = listOf(check(caseId, CheckType.PEP_SCREENING, CheckStatus.PENDING)),
            ).copy(riskLevel = RiskLevel.MEDIUM)
            coEvery { repo.findById(caseId) } returns existing
            coEvery { repo.update(any<KycCase>(), any()) } answers { firstArg<KycCase>() }
            // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
            coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }
            // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
            coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }

            val result = service.applyPepScreeningResult(caseId, PepScreeningStatus.MATCH, 0.97, "Andrej Babiš")

            val pepCheck = result.checks.single { it.checkType == CheckType.PEP_SCREENING }
            assertThat(pepCheck.status).isEqualTo(CheckStatus.MANUAL_REVIEW)
            assertThat(pepCheck.result).contains("Andrej Babiš")
            assertThat(result.riskLevel).isEqualTo(RiskLevel.HIGH)
            coVerify(exactly = 1) {
                repo.update(
                    any<KycCase>(),
                    match<KycEvent> {
                        it.eventType ==
                            "KYC_CASE_STATUS_CHANGED"
                    },
                )
            }
        }

    @Test
    fun `applyPepScreeningResult never downgrades an already VERY_HIGH risk level on a match`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        val existing = kycCase(
            id = caseId,
            checks = listOf(check(caseId, CheckType.PEP_SCREENING, CheckStatus.PENDING)),
        ).copy(riskLevel = RiskLevel.VERY_HIGH)
        coEvery { repo.findById(caseId) } returns existing
        coEvery { repo.update(any<KycCase>(), any()) } answers { firstArg<KycCase>() }
        // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
        coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }

        val result = service.applyPepScreeningResult(caseId, PepScreeningStatus.MATCH, 0.99, "Some PEP")

        assertThat(result.riskLevel).isEqualTo(RiskLevel.VERY_HIGH)
    }

    @Test
    fun `applyPepScreeningResult routes a POTENTIAL_MATCH to MANUAL_REVIEW and escalates risk too`(): Unit =
        runBlocking {
            val caseId = UUID.randomUUID()
            val existing = kycCase(
                id = caseId,
                checks = listOf(check(caseId, CheckType.PEP_SCREENING, CheckStatus.PENDING)),
            )
            coEvery { repo.findById(caseId) } returns existing
            coEvery { repo.update(any<KycCase>(), any()) } answers { firstArg<KycCase>() }
            // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
            coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }
            // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
            coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }

            val result = service.applyPepScreeningResult(
                caseId,
                PepScreeningStatus.POTENTIAL_MATCH,
                0.7,
                "Similar Name",
            )

            val pepCheck = result.checks.single { it.checkType == CheckType.PEP_SCREENING }
            assertThat(pepCheck.status).isEqualTo(CheckStatus.MANUAL_REVIEW)
            assertThat(result.riskLevel).isEqualTo(RiskLevel.HIGH)
        }

    @Test
    fun `applyPepScreeningResult routes UNAVAILABLE to MANUAL_REVIEW instead of a silent PASSED`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        val existing = kycCase(
            id = caseId,
            checks = listOf(check(caseId, CheckType.PEP_SCREENING, CheckStatus.PENDING)),
        )
        coEvery { repo.findById(caseId) } returns existing
        coEvery { repo.update(any<KycCase>(), any()) } answers { firstArg<KycCase>() }
        // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
        coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }

        val result = service.applyPepScreeningResult(caseId, PepScreeningStatus.UNAVAILABLE, 0.0, null)

        val pepCheck = result.checks.single { it.checkType == CheckType.PEP_SCREENING }
        assertThat(pepCheck.status).isEqualTo(CheckStatus.MANUAL_REVIEW)
        assertThat(pepCheck.status).isNotEqualTo(CheckStatus.PASSED)
        // Unavailability alone is not a PEP hit — it must not misrepresent risk as escalated.
        assertThat(result.riskLevel).isEqualTo(RiskLevel.MEDIUM)
    }

    @Test
    fun `applyPepScreeningResult a clean re-screen after a prior match does not re-escalate further`(): Unit =
        runBlocking {
            // Simulates re-screening (operator-triggered pep-rescreen endpoint) once the PEP list
            // has been updated/corrected: a case already HIGH from a prior match stays HIGH (never
            // silently downgraded here — an operator must still clear the case explicitly), but a
            // fresh CLEAR result does move the check itself back to PASSED.
            val caseId = UUID.randomUUID()
            val existing = kycCase(
                id = caseId,
                checks = listOf(check(caseId, CheckType.PEP_SCREENING, CheckStatus.MANUAL_REVIEW)),
            ).copy(riskLevel = RiskLevel.HIGH)
            coEvery { repo.findById(caseId) } returns existing
            coEvery { repo.update(any<KycCase>(), any()) } answers { firstArg<KycCase>() }
            // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
            coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }
            // No status/risk change ⇒ the service must take the event-FREE overload (no event is due).
            coEvery { repo.update(any<KycCase>()) } answers { firstArg<KycCase>() }

            val result = service.applyPepScreeningResult(caseId, PepScreeningStatus.CLEAR, 0.05, null)

            val pepCheck = result.checks.single { it.checkType == CheckType.PEP_SCREENING }
            assertThat(pepCheck.status).isEqualTo(CheckStatus.PASSED)
            assertThat(result.riskLevel).isEqualTo(RiskLevel.HIGH)
        }

    @Test
    fun `applyPepScreeningResult throws KycCaseNotFoundException when case does not exist`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        coEvery { repo.findById(caseId) } returns null

        assertThatThrownBy {
            runBlocking { service.applyPepScreeningResult(caseId, PepScreeningStatus.CLEAR, 0.0, null) }
        }.isInstanceOf(KycCaseNotFoundException::class.java)
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

    @Test
    fun `a BUSINESS subject gets the KYB check set and counts as a business submission`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.findActiveByPartyId(partyId) } returns null
        val saved = io.mockk.slot<KycCase>()
        coEvery { repo.save(capture(saved), any()) } answers { firstArg() }

        val (case, created) = service.openCaseForParty(partyId, com.openbank.kyc.domain.model.SubjectType.BUSINESS)

        assertThat(created).isTrue()
        assertThat(case.subjectType).isEqualTo(com.openbank.kyc.domain.model.SubjectType.BUSINESS)
        assertThat(case.checks.map { it.checkType }).containsExactly(
            CheckType.REGISTRY_MATCH,
            CheckType.REPRESENTATIVE_AUTHORITY,
            CheckType.UBO_IDENTIFICATION,
            CheckType.SANCTIONS_SCREENING,
            CheckType.ADVERSE_MEDIA,
        )
        assertThat(case.checks.map { it.checkType }).doesNotContain(CheckType.IDENTITY, CheckType.ADDRESS)
        io.mockk.verify { metrics.kycSubmitted("business") }
    }

    @Test
    fun `party type on PARTY_CREATED decides the subject type`() {
        assertThat(
            com.openbank.kyc.domain.model.SubjectType.fromPartyType("COMPANY"),
        ).isEqualTo(com.openbank.kyc.domain.model.SubjectType.BUSINESS)
        assertThat(
            com.openbank.kyc.domain.model.SubjectType.fromPartyType("SOLE_TRADER"),
        ).isEqualTo(com.openbank.kyc.domain.model.SubjectType.BUSINESS)
        assertThat(
            com.openbank.kyc.domain.model.SubjectType.fromPartyType("INDIVIDUAL"),
        ).isEqualTo(com.openbank.kyc.domain.model.SubjectType.INDIVIDUAL)
        assertThat(
            com.openbank.kyc.domain.model.SubjectType.fromPartyType(null),
        ).isEqualTo(com.openbank.kyc.domain.model.SubjectType.INDIVIDUAL)
    }
}
