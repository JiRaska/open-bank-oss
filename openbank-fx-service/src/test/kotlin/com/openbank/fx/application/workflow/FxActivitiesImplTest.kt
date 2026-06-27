// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.workflow

import com.openbank.fx.application.port.out.AmlCasePort
import com.openbank.fx.application.port.out.FraudScoreOutcome
import com.openbank.fx.application.port.out.FraudScoringPort
import com.openbank.fx.application.port.out.FraudVerdict
import com.openbank.fx.application.port.out.FxConversionRepository
import com.openbank.fx.application.port.out.SanctionsScreeningPort
import com.openbank.fx.application.port.out.ScreeningUnavailableException
import com.openbank.fx.domain.model.FxConversion
import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.fx.domain.screening.ScreeningDecision
import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningResult
import com.openbank.fx.domain.screening.ScreeningRole
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FxActivitiesImplTest {

    private val conversionRepository: FxConversionRepository = mockk()
    private val screeningPort: SanctionsScreeningPort = mockk()
    private val amlCasePort: AmlCasePort = mockk()
    private val fraudScoringPort: FraudScoringPort = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)

    private val activities: FxActivitiesImpl = object : FxActivitiesImpl(
        conversionRepository,
        screeningPort,
        amlCasePort,
        fraudScoringPort,
        clock,
    ) {
        override fun <T> vtx(block: suspend () -> T): T = runBlocking { block() }
    }

    private fun aConversion(id: UUID = UUID.randomUUID(), status: FxConversionStatus = FxConversionStatus.PENDING) =
        FxConversion(
            id = id,
            idempotencyKey = "key-$id",
            partyId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            fromCurrency = "CZK",
            toCurrency = "EUR",
            fromAmountMinorUnits = 10_000L,
            toAmountMinorUnits = 400L,
            appliedRate = BigDecimal("0.04"),
            feeMinorUnits = 50L,
            rateId = UUID.randomUUID(),
            status = status,
            createdAt = Instant.EPOCH,
            settledAt = null,
        )

    @Test
    fun `screenConversion returns CLEAR when sanctions clear`() {
        val conv = aConversion()
        coEvery { conversionRepository.findById(conv.id) } returns conv
        coEvery { screeningPort.screen(any(), any(), any()) } returns
            ScreeningResult("name", ScreeningRole.DEBTOR, ScreeningMatchStatus.CLEAR, 0.0, null)

        val decision = activities.screenConversion(conv.id)

        assertThat(decision).isEqualTo(ScreeningDecision.CLEAR)
    }

    @Test
    fun `screenConversion returns REVIEW and opens AML case when screening unavailable`() {
        val conv = aConversion()
        coEvery { conversionRepository.findById(conv.id) } returns conv
        coEvery {
            screeningPort.screen(any(), any(), any())
        } throws ScreeningUnavailableException(RuntimeException("timeout"))
        coEvery { amlCasePort.openCase(any()) } just Runs

        val decision = activities.screenConversion(conv.id)

        assertThat(decision).isEqualTo(ScreeningDecision.REVIEW)
        coVerify(exactly = 1) { amlCasePort.openCase(any()) }
    }

    @Test
    fun `settleConversion transitions to SETTLED`() {
        val conv = aConversion(status = FxConversionStatus.PENDING)
        coEvery { conversionRepository.findById(conv.id) } returns conv
        coEvery { conversionRepository.save(any()) } answers { firstArg() }

        activities.settleConversion(conv.id)

        coVerify(exactly = 1) { conversionRepository.save(match { it.status == FxConversionStatus.SETTLED }) }
    }

    @Test
    fun `settleConversion is idempotent when already SETTLED`() {
        val conv = aConversion(status = FxConversionStatus.SETTLED)
        coEvery { conversionRepository.findById(conv.id) } returns conv

        activities.settleConversion(conv.id)

        coVerify(exactly = 0) { conversionRepository.save(any()) }
    }

    @Test
    fun `blockConversion transitions to FAILED`() {
        val conv = aConversion(status = FxConversionStatus.PENDING)
        coEvery { conversionRepository.findById(conv.id) } returns conv
        coEvery { conversionRepository.save(any()) } answers { firstArg() }

        activities.blockConversion(conv.id)

        coVerify(exactly = 1) { conversionRepository.save(match { it.status == FxConversionStatus.FAILED }) }
    }

    @Test
    fun `holdConversion is idempotent when already PENDING`() {
        val conv = aConversion(status = FxConversionStatus.PENDING)
        coEvery { conversionRepository.findById(conv.id) } returns conv

        activities.holdConversion(conv.id)

        coVerify(exactly = 0) { conversionRepository.save(any()) }
    }

    @Test
    fun `shadowFraudScore completes without error`() {
        val conv = aConversion()
        coEvery { conversionRepository.findById(conv.id) } returns conv
        coEvery { fraudScoringPort.score(any()) } returns
            FraudScoreOutcome(verdict = FraudVerdict.ALLOW, score = 10, ruleVersion = "v1", reasons = emptyList())

        activities.shadowFraudScore(conv.id)

        coVerify(exactly = 1) { fraudScoringPort.score(any()) }
    }
}
