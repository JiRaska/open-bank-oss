// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.PepScreeningPort
import com.openbank.kyc.application.port.out.PepScreeningResult
import com.openbank.kyc.application.port.out.PepScreeningStatus
import com.openbank.kyc.application.port.out.PepScreeningUnavailableException
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.RiskLevel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Orchestration tests for [PepScreeningService] — the first-increment PEP check (ADR-0116
 * delivery note). Verifies it dispatches to [PepScreeningPort] with the right idempotency key and
 * degrades a port-level outage to [PepScreeningStatus.UNAVAILABLE] instead of propagating the
 * exception (a downstream sanctions-service outage must never abort a KYC case open/re-screen).
 */
class PepScreeningServiceTest {

    private val screeningPort = mockk<PepScreeningPort>()
    private val kycService = mockk<KycService>()

    private lateinit var service: PepScreeningService

    @BeforeEach
    fun setUp() {
        service = PepScreeningService().also {
            it.screeningPort = screeningPort
            it.kycService = kycService
        }
    }

    @Test
    fun `screenCase passes a CLEAR result through to KycService`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        coEvery { screeningPort.screenForPep("Jane Clean", "kyc-pep-$caseId") } returns
            PepScreeningResult(PepScreeningStatus.CLEAR, 0.1, null)
        coEvery {
            kycService.applyPepScreeningResult(caseId, PepScreeningStatus.CLEAR, 0.1, null)
        } returns caseWithPepCheck(caseId, CheckStatus.PASSED, RiskLevel.MEDIUM)

        val result = service.screenCase(caseId, "Jane Clean")

        assertThat(result.riskLevel).isEqualTo(RiskLevel.MEDIUM)
        coVerify(exactly = 1) { screeningPort.screenForPep("Jane Clean", "kyc-pep-$caseId") }
        coVerify(exactly = 1) { kycService.applyPepScreeningResult(caseId, PepScreeningStatus.CLEAR, 0.1, null) }
    }

    @Test
    fun `screenCase passes a MATCH result through to KycService for a known PEP name`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        coEvery { screeningPort.screenForPep("Andrej Babiš", "kyc-pep-$caseId") } returns
            PepScreeningResult(PepScreeningStatus.MATCH, 0.97, "Andrej Babiš")
        coEvery {
            kycService.applyPepScreeningResult(caseId, PepScreeningStatus.MATCH, 0.97, "Andrej Babiš")
        } returns caseWithPepCheck(caseId, CheckStatus.MANUAL_REVIEW, RiskLevel.HIGH)

        val result = service.screenCase(caseId, "Andrej Babiš")

        assertThat(result.riskLevel).isEqualTo(RiskLevel.HIGH)
        assertThat(result.checks.single().status).isEqualTo(CheckStatus.MANUAL_REVIEW)
    }

    @Test
    fun `screenCase degrades a downstream outage to UNAVAILABLE instead of throwing`(): Unit = runBlocking {
        val caseId = UUID.randomUUID()
        coEvery { screeningPort.screenForPep("Jane Doe", "kyc-pep-$caseId") } throws
            PepScreeningUnavailableException(RuntimeException("connection refused"))
        coEvery {
            kycService.applyPepScreeningResult(caseId, PepScreeningStatus.UNAVAILABLE, 0.0, null)
        } returns caseWithPepCheck(caseId, CheckStatus.MANUAL_REVIEW, RiskLevel.MEDIUM)

        val result = service.screenCase(caseId, "Jane Doe")

        assertThat(result.checks.single().status).isEqualTo(CheckStatus.MANUAL_REVIEW)
        coVerify(exactly = 1) {
            kycService.applyPepScreeningResult(caseId, PepScreeningStatus.UNAVAILABLE, 0.0, null)
        }
    }

    private fun caseWithPepCheck(caseId: UUID, checkStatus: CheckStatus, riskLevel: RiskLevel) = KycCase(
        id = caseId,
        partyId = UUID.randomUUID(),
        status = KycCaseStatus.OPEN,
        riskLevel = riskLevel,
        assignedTo = null,
        checks = listOf(
            KycCheck(
                id = UUID.randomUUID(),
                caseId = caseId,
                checkType = CheckType.PEP_SCREENING,
                status = checkStatus,
                result = null,
                provider = "openbank-sanctions-service:PEP_GLOBAL",
                performedAt = Instant.parse("2026-07-01T00:00:00Z"),
                createdAt = Instant.parse("2026-07-01T00:00:00Z"),
            ),
        ),
        notes = null,
        reviewedBy = null,
        reviewedAt = null,
        expiresAt = Instant.parse("2026-08-01T00:00:00Z"),
        createdAt = Instant.parse("2026-07-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-07-01T00:00:00Z"),
    )
}
