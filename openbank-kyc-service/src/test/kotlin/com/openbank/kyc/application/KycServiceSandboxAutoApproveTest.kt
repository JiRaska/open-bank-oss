// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.AdverseMediaScreeningPort
import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.RiskLevel
import com.openbank.kyc.domain.model.SubjectType
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The sandbox straight-through approval (`openbank.kyc.auto-approve`) may approve only a case an
 * actual customer screen settled clear (issue #15723). Before, it approved every case the moment
 * it was opened, before any screen had run — a sanctioned name included.
 */
class KycServiceSandboxAutoApproveTest {

    private val repo = mockk<KycCaseRepository>()
    private val adverseMediaSource = mockk<AdverseMediaScreeningPort>()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: KycService

    @BeforeEach
    fun setUp() {
        every { adverseMediaSource.sourceId } returns null
        service = KycService().also {
            it.repo = repo
            it.metrics = mockk<DomainMetrics>(relaxed = true)
            it.clock = clock
            it.adverseMediaSource = adverseMediaSource
            it.autoApprove = true
        }
        coEvery { repo.isErased(any()) } returns false
    }

    private fun case(sanctions: CheckStatus, provider: String?, pep: CheckStatus? = null): KycCase {
        val id = UUID.randomUUID()
        val at = Instant.now(clock)
        fun check(type: CheckType, status: CheckStatus, result: String?, by: String?) =
            KycCheck(UUID.randomUUID(), id, type, status, result, by, null, at)
        return KycCase(
            id = id,
            partyId = UUID.randomUUID(),
            status = KycCaseStatus.OPEN,
            riskLevel = RiskLevel.MEDIUM,
            assignedTo = null,
            checks = listOfNotNull(
                check(CheckType.SANCTIONS_SCREENING, sanctions, "screened", provider),
                pep?.let { check(CheckType.PEP_SCREENING, it, "screened", null) },
                check(CheckType.ADVERSE_MEDIA, CheckStatus.MANUAL_REVIEW, "SOURCE_NOT_CONFIGURED", null),
            ),
            notes = null,
            reviewedBy = null,
            reviewedAt = null,
            expiresAt = null,
            createdAt = at,
            updatedAt = at,
            subjectType = SubjectType.BUSINESS,
        )
    }

    private fun result(case: KycCase, type: CheckType) = case.checks.single { it.checkType == type }.result

    @Test
    fun `a case screened clear is approved and keeps its real screening result`(): Unit = runBlocking {
        val case = case(CheckStatus.PASSED, KycCase.CUSTOMER_SCREENING_PROVIDER, pep = CheckStatus.PASSED)
        coEvery { repo.findById(case.id) } returns case
        coEvery { repo.update(any(), any()) } answers { firstArg() }

        val approved = service.sandboxAutoApprove(case.id)

        assertThat(approved.status).isEqualTo(KycCaseStatus.APPROVED)
        assertThat(approved.reviewedBy).isEqualTo("sandbox-auto-approval")
        assertThat(approved.checks).allMatch { it.status == CheckStatus.PASSED }
        assertThat(result(approved, CheckType.SANCTIONS_SCREENING)).isEqualTo("screened")
        assertThat(result(approved, CheckType.ADVERSE_MEDIA)).isEqualTo("sandbox-auto")
    }

    @Test
    fun `a case that was not screened clear is left exactly as it is`(): Unit = runBlocking {
        val screened = KycCase.CUSTOMER_SCREENING_PROVIDER
        val refused = listOf(
            case(CheckStatus.MANUAL_REVIEW, screened), // sanctions match
            case(CheckStatus.PENDING, null), // screening unavailable, or no name to screen
            case(CheckStatus.PASSED, null), // passed, but not by a screen
            case(CheckStatus.PASSED, screened, pep = CheckStatus.MANUAL_REVIEW), // PEP match
        )
        for (case in refused) {
            coEvery { repo.findById(case.id) } returns case

            assertThat(service.sandboxAutoApprove(case.id)).isEqualTo(case)
        }
        coVerify(exactly = 0) { repo.update(any(), any()) }
    }

    @Test
    fun `nothing is approved when the flag is off, even a case screened clear`(): Unit = runBlocking {
        service.autoApprove = false
        val case = case(CheckStatus.PASSED, KycCase.CUSTOMER_SCREENING_PROVIDER)
        coEvery { repo.findById(case.id) } returns case

        assertThat(service.sandboxAutoApprove(case.id).status).isEqualTo(KycCaseStatus.OPEN)
        coVerify(exactly = 0) { repo.update(any(), any()) }
    }

    @Test
    fun `opening a case for a party never approves it, whatever the flag says`(): Unit = runBlocking {
        coEvery { repo.findActiveByPartyId(any()) } returns null
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        val opened = service.openCaseForParty(UUID.randomUUID()).case

        assertThat(opened.status).isEqualTo(KycCaseStatus.OPEN)
        coVerify(exactly = 0) { repo.update(any(), any()) }
    }

    @Test
    fun `an erased case is never approved, even when it was screened clear`(): Unit = runBlocking {
        val case = case(CheckStatus.PASSED, KycCase.CUSTOMER_SCREENING_PROVIDER, pep = CheckStatus.PASSED)
        coEvery { repo.findById(case.id) } returns case
        coEvery { repo.isErased(case.id) } returns true

        val untouched = service.sandboxAutoApprove(case.id)

        assertThat(untouched.status).isEqualTo(case.status)
        coVerify(exactly = 0) { repo.update(any()) }
        coVerify(exactly = 0) { repo.update(any(), any()) }
    }
}
