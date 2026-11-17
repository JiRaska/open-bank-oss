// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.PepScreeningPort
import com.openbank.kyc.application.port.out.PepScreeningResult
import com.openbank.kyc.application.port.out.PepScreeningStatus
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.RiskLevel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The idempotency key of the operator PEP re-screen.
 *
 * sanctions-service answers a repeated idempotency key with the check it stored the first time
 * (`findByIdempotencyKey` returns before any list is consulted). A re-screen that reuses one key
 * per case therefore never sees a refreshed list: it replays the first answer for the life of the
 * case, while reporting a fresh `performedAt`.
 *
 * So the key carries the version of the PEP check the re-screen is about to replace. A re-screen
 * after a recorded result is a new screen; a retry of a request whose result was never recorded
 * still carries the same key and is replayed by the provider, not duplicated.
 */
class PepRescreenIdempotencyKeyTest {

    private val caseId: UUID = UUID.fromString("5a0e4c55-21f4-4a3e-9d0c-6f1c5f0f4b11")
    private val keys = mutableListOf<String>()
    private val screeningPort = mockk<PepScreeningPort>()
    private val kycService = mockk<KycService>()
    private val service = PepScreeningService().also {
        it.screeningPort = screeningPort
        it.kycService = kycService
    }

    /** The stored case: its PEP check carries the time of the last recorded result, if any. */
    private var stored: KycCase = case(performedAt = null)

    init {
        coEvery { screeningPort.screenForPep(any(), capture(keys)) } returns
            PepScreeningResult(PepScreeningStatus.CLEAR, 0.0, null)
        coEvery { kycService.getCase(caseId) } answers { stored }
    }

    private fun recordResultAt(instant: String) {
        coEvery { kycService.applyPepScreeningResult(caseId, any(), any(), any()) } answers {
            stored = case(performedAt = Instant.parse(instant))
            stored
        }
    }

    @Test
    fun `each re-screen after a recorded result is a distinct screen`(): Unit = runBlocking {
        recordResultAt("2026-11-01T10:00:00Z")
        service.screenCase(caseId, "Jane Clean")
        recordResultAt("2026-11-09T08:30:00Z")
        service.screenCase(caseId, "Jane Clean")
        service.screenCase(caseId, "Jane Clean")

        assertThat(keys).hasSize(3)
        assertThat(keys.toSet())
            .describedAs("a reused key makes the provider replay its first answer instead of screening again")
            .hasSize(3)
        assertThat(keys).allSatisfy { assertThat(it).startsWith("kyc-pep-$caseId") }
    }

    @Test
    fun `a retry whose result was never recorded reuses the key, so the provider replays it`(): Unit = runBlocking {
        stored = case(performedAt = Instant.parse("2026-11-01T10:00:00Z"))
        coEvery { kycService.applyPepScreeningResult(caseId, any(), any(), any()) } throws
            IllegalStateException("database unavailable — result not recorded")

        repeat(2) { runCatching { service.screenCase(caseId, "Jane Clean") } }

        assertThat(keys).hasSize(2)
        assertThat(keys[0]).isEqualTo(keys[1])
    }

    private fun case(performedAt: Instant?) = KycCase(
        id = caseId,
        partyId = UUID.fromString("0b0d3f0e-7f43-4f43-8a52-0d7a7a0b6c22"),
        status = KycCaseStatus.OPEN,
        riskLevel = RiskLevel.MEDIUM,
        assignedTo = null,
        checks = listOf(
            KycCheck(
                id = UUID.fromString("9c1f2e7a-58a1-4c59-8f43-2f0a6b1d7e33"),
                caseId = caseId,
                checkType = CheckType.PEP_SCREENING,
                status = if (performedAt == null) CheckStatus.PENDING else CheckStatus.PASSED,
                result = null,
                provider = null,
                performedAt = performedAt,
                createdAt = Instant.parse("2026-10-01T00:00:00Z"),
            ),
        ),
        notes = null,
        reviewedBy = null,
        reviewedAt = null,
        expiresAt = null,
        createdAt = Instant.parse("2026-10-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-10-01T00:00:00Z"),
    )
}
