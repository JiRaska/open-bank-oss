// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.CustomerScreeningPort
import com.openbank.kyc.application.port.out.CustomerScreeningRequest
import com.openbank.kyc.application.port.out.CustomerScreeningResult
import com.openbank.kyc.application.port.out.CustomerScreeningUnavailableException
import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.application.port.out.PepScreeningResult
import com.openbank.kyc.application.port.out.PepScreeningStatus
import com.openbank.kyc.application.port.out.SanctionsScreeningOutcome
import com.openbank.kyc.application.port.out.SanctionsScreeningStatus
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.KycEvent
import com.openbank.kyc.domain.model.RiskLevel
import com.openbank.kyc.domain.model.SubjectType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CustomerScreeningServiceTest {

    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val port = mockk<CustomerScreeningPort>()
    private val repo = mockk<KycCaseRepository>()
    private val kycService = mockk<KycService>()
    private val service = CustomerScreeningService(port, repo, kycService, Clock.fixed(now, ZoneOffset.UTC))

    private val clearPep = PepScreeningResult(PepScreeningStatus.CLEAR, 0.0, null)

    private fun case(
        sanctions: CheckStatus = CheckStatus.PENDING,
        subjectType: SubjectType = SubjectType.INDIVIDUAL,
        others: List<CheckType> = listOf(CheckType.PEP_SCREENING),
    ): KycCase {
        val id = UUID.randomUUID()
        fun check(type: CheckType, status: CheckStatus) =
            KycCheck(UUID.randomUUID(), id, type, status, null, null, null, now)
        return KycCase(
            id = id, partyId = UUID.randomUUID(), status = KycCaseStatus.OPEN, riskLevel = RiskLevel.MEDIUM,
            assignedTo = null,
            checks =
            listOf(check(CheckType.SANCTIONS_SCREENING, sanctions)) + others.map { check(it, CheckStatus.PENDING) },
            notes = null, reviewedBy = null, reviewedAt = null, expiresAt = null, createdAt = now, updatedAt = now,
            subjectType = subjectType,
        )
    }

    private fun stubRepo(case: KycCase) {
        coEvery { repo.findById(case.id) } returns case
        coEvery { repo.update(any()) } answers { firstArg() }
        coEvery { repo.update(any(), any<KycEvent>()) } answers { firstArg() }
        coEvery { kycService.applyPepScreeningResult(any(), any(), any(), any()) } answers {
            // The PEP application is KycService's own, tested there; here it returns what was saved.
            savedOrOriginal(case)
        }
    }

    private val saved = slot<KycCase>()
    private fun savedOrOriginal(case: KycCase) = if (saved.isCaptured) saved.captured else case

    private fun sanctionsCheck(case: KycCase) = case.checks.single { it.checkType == CheckType.SANCTIONS_SCREENING }

    @Test
    fun `a clear screen passes the sanctions check and records the screening provider`(): Unit = runBlocking {
        val case = case()
        stubRepo(case)
        coEvery { repo.update(capture(saved)) } answers { firstArg() }
        coEvery { port.screenCustomer(any()) } returns
            CustomerScreeningResult(SanctionsScreeningOutcome(SanctionsScreeningStatus.CLEAR, 0.0, null), clearPep)

        service.screenOnOpen(case, "Jane Clean")

        val check = sanctionsCheck(saved.captured)
        assertThat(check.status).isEqualTo(CheckStatus.PASSED)
        assertThat(check.provider).isEqualTo(KycCase.CUSTOMER_SCREENING_PROVIDER)
        assertThat(check.performedAt).isEqualTo(now)
        assertThat(saved.captured.riskLevel).isEqualTo(RiskLevel.MEDIUM)
    }

    @Test
    fun `the request carries the party as subject and a per-case idempotency key`(): Unit = runBlocking {
        val case = case(subjectType = SubjectType.BUSINESS, others = emptyList())
        stubRepo(case)
        val request = slot<CustomerScreeningRequest>()
        coEvery { port.screenCustomer(capture(request)) } returns
            CustomerScreeningResult(SanctionsScreeningOutcome(SanctionsScreeningStatus.CLEAR, 0.0, null), clearPep)

        service.screenOnOpen(case, "Synthetic Company")

        assertThat(request.captured).isEqualTo(
            CustomerScreeningRequest("Synthetic Company", case.partyId.toString(), true, "kyc-screen-${case.id}"),
        )
        // A business case has no PEP check, so no PEP result is applied to it.
        coVerify(exactly = 0) { kycService.applyPepScreeningResult(any(), any(), any(), any()) }
    }

    @Test
    fun `a sanctions match goes to manual review at the highest risk tier, with an event`(): Unit = runBlocking {
        val case = case()
        stubRepo(case)
        coEvery { repo.update(capture(saved), any<KycEvent>()) } answers { firstArg() }
        coEvery { port.screenCustomer(any()) } returns CustomerScreeningResult(
            SanctionsScreeningOutcome(SanctionsScreeningStatus.MATCH, 0.97, "Synthetic Listed"),
            clearPep,
        )

        service.screenOnOpen(case, "Synthetic Listed")

        assertThat(sanctionsCheck(saved.captured).status).isEqualTo(CheckStatus.MANUAL_REVIEW)
        assertThat(saved.captured.riskLevel).isEqualTo(RiskLevel.VERY_HIGH)
        assertThat(saved.captured.status).isEqualTo(KycCaseStatus.OPEN)
    }

    @Test
    fun `a potential match goes to manual review and raises the tier to at least HIGH`(): Unit = runBlocking {
        val case = case()
        stubRepo(case)
        coEvery { repo.update(capture(saved), any<KycEvent>()) } answers { firstArg() }
        coEvery { port.screenCustomer(any()) } returns CustomerScreeningResult(
            SanctionsScreeningOutcome(SanctionsScreeningStatus.POTENTIAL_MATCH, 0.7, "Synthetic Similar"),
            clearPep,
        )

        service.screenOnOpen(case, "Synthetic Similar")

        assertThat(sanctionsCheck(saved.captured).status).isEqualTo(CheckStatus.MANUAL_REVIEW)
        assertThat(saved.captured.riskLevel).isEqualTo(RiskLevel.HIGH)
    }

    @Test
    fun `an unavailable screen leaves the check PENDING and reports PEP as unavailable`(): Unit = runBlocking {
        val case = case()
        stubRepo(case)
        coEvery { repo.update(capture(saved)) } answers { firstArg() }
        coEvery { port.screenCustomer(any()) } throws CustomerScreeningUnavailableException(RuntimeException("down"))

        service.screenOnOpen(case, "Jane Doe")

        val check = sanctionsCheck(saved.captured)
        assertThat(check.status).isEqualTo(CheckStatus.PENDING)
        assertThat(check.performedAt).isNull()
        assertThat(check.provider).isNull()
        assertThat(saved.captured.hasClearScreen()).isFalse()
        coVerify { kycService.applyPepScreeningResult(case.id, PepScreeningStatus.UNAVAILABLE, 0.0, null) }
    }

    @Test
    fun `a check a reviewer already decided is not overwritten by a redelivered event`(): Unit = runBlocking {
        val case = case(sanctions = CheckStatus.PASSED)
        stubRepo(case)
        coEvery { port.screenCustomer(any()) } returns CustomerScreeningResult(
            SanctionsScreeningOutcome(SanctionsScreeningStatus.MATCH, 0.97, "Synthetic Listed"),
            clearPep,
        )

        service.screenOnOpen(case, "Synthetic Listed")

        coVerify(exactly = 0) { repo.update(any()) }
        coVerify(exactly = 0) { repo.update(any(), any<KycEvent>()) }
    }
}
