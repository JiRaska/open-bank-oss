// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.KycEvent
import com.openbank.kyc.domain.model.RiskLevel
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

/** A customer's AML declaration with risk factors routes the open KYC case to EDD (party-service #10200). */
class KycServiceDeclaredAmlRiskTest {

    private val now = Instant.parse("2026-09-17T10:00:00Z")
    private val repo = mockk<KycCaseRepository>()
    private val service = KycService().also {
        it.repo = repo
        it.metrics = mockk(relaxed = true)
        it.clock = Clock.fixed(now, ZoneOffset.UTC)
    }
    private val partyId = UUID.randomUUID()

    private fun case(
        status: KycCaseStatus = KycCaseStatus.OPEN,
        risk: RiskLevel = RiskLevel.MEDIUM,
        pep: CheckStatus = CheckStatus.PENDING,
        notes: String? = "opened by onboarding",
    ): KycCase {
        val id = UUID.randomUUID()
        return KycCase(
            id = id, partyId = partyId, status = status, riskLevel = risk, assignedTo = null,
            checks = listOf(
                KycCheck(UUID.randomUUID(), id, CheckType.IDENTITY, CheckStatus.PASSED, null, null, null, now),
                KycCheck(UUID.randomUUID(), id, CheckType.PEP_SCREENING, pep, "clear", null, null, now),
            ),
            notes = notes, reviewedBy = null, reviewedAt = null, expiresAt = null, createdAt = now, updatedAt = now,
        )
    }

    private fun stubUpdate(): io.mockk.CapturingSlot<KycCase> {
        val saved = slot<KycCase>()
        coEvery { repo.update(capture(saved), any<KycEvent>()) } answers { firstArg() }
        return saved
    }

    @Test
    fun `a declared PEP floors risk at HIGH, puts the PEP check into manual review and records why`(): Unit =
        runBlocking {
            coEvery { repo.findActiveByPartyId(partyId) } returns case(pep = CheckStatus.PASSED)
            val saved = stubUpdate()

            val result = service.escalateForDeclaredAmlRisk(partyId, listOf("PEP", "HIGH_TURNOVER"))

            assertThat(result).isNotNull
            assertThat(saved.captured.riskLevel).isEqualTo(RiskLevel.HIGH)
            assertThat(saved.captured.status).isEqualTo(KycCaseStatus.OPEN)
            val pepCheck = saved.captured.checks.single { it.checkType == CheckType.PEP_SCREENING }
            assertThat(pepCheck.status).isEqualTo(CheckStatus.MANUAL_REVIEW)
            assertThat(pepCheck.result).contains("self-declared PEP")
            assertThat(saved.captured.checks.single { it.checkType == CheckType.IDENTITY }.status)
                .isEqualTo(CheckStatus.PASSED)
            assertThat(saved.captured.notes)
                .startsWith("opened by onboarding\n")
                .contains("EDD: customer AML declaration risk factors [PEP,HIGH_TURNOVER]")
        }

    @Test
    fun `a non-PEP risk factor escalates risk but leaves the PEP check alone`(): Unit = runBlocking {
        coEvery { repo.findActiveByPartyId(partyId) } returns case(risk = RiskLevel.LOW)
        val saved = stubUpdate()

        service.escalateForDeclaredAmlRisk(partyId, listOf("US_PERSON"))

        assertThat(saved.captured.riskLevel).isEqualTo(RiskLevel.HIGH)
        assertThat(saved.captured.checks.single { it.checkType == CheckType.PEP_SCREENING }.status)
            .isEqualTo(CheckStatus.PENDING)
    }

    @Test
    fun `a terminal case - the sandbox auto-approve path - is left exactly as today`(): Unit = runBlocking {
        coEvery { repo.findActiveByPartyId(partyId) } returns case(status = KycCaseStatus.APPROVED)

        assertThat(service.escalateForDeclaredAmlRisk(partyId, listOf("PEP"))).isNull()
        coVerify(exactly = 0) { repo.update(any(), any<KycEvent>()) }
    }

    @Test
    fun `no active case means nothing to route`(): Unit = runBlocking {
        coEvery { repo.findActiveByPartyId(partyId) } returns null

        assertThat(service.escalateForDeclaredAmlRisk(partyId, listOf("PEP"))).isNull()
        coVerify(exactly = 0) { repo.update(any(), any<KycEvent>()) }
    }

    @Test
    fun `a replayed declaration changes nothing and VERY_HIGH is never downgraded`(): Unit = runBlocking {
        val already = case(
            risk = RiskLevel.VERY_HIGH,
            pep = CheckStatus.MANUAL_REVIEW,
            notes = "EDD: customer AML declaration risk factors [PEP]",
        )
        coEvery { repo.findActiveByPartyId(partyId) } returns already

        assertThat(service.escalateForDeclaredAmlRisk(partyId, listOf("PEP"))).isNull()
        coVerify(exactly = 0) { repo.update(any(), any<KycEvent>()) }
    }
}
