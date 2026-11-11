// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.workflow

import com.openbank.fx.application.port.out.AmlCasePort
import com.openbank.fx.application.port.out.FraudScoreOutcome
import com.openbank.fx.application.port.out.FraudScoringPort
import com.openbank.fx.application.port.out.FxConversionRepository
import com.openbank.fx.application.port.out.PartyNamePort
import com.openbank.fx.application.port.out.PartyNameUnavailableException
import com.openbank.fx.application.port.out.SanctionsScreeningPort
import com.openbank.fx.application.port.out.ScreeningUnavailableException
import com.openbank.fx.domain.model.FxConversion
import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.libs.fincrime.FraudVerdict
import com.openbank.libs.fincrime.ScreeningDecision
import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FxActivitiesImplTest {

    private val conversionRepository: FxConversionRepository = mockk()
    private val screeningPort: SanctionsScreeningPort = mockk()
    private val partyNamePort: PartyNamePort = mockk()
    private val amlCasePort: AmlCasePort = mockk()
    private val fraudScoringPort: FraudScoringPort = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)

    private val activities: FxActivitiesImpl = object : FxActivitiesImpl(
        conversionRepository,
        screeningPort,
        partyNamePort,
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

    @BeforeEach
    fun partyHasAName() {
        coEvery { partyNamePort.screeningNames(any()) } returns listOf(PARTY_NAME)
    }

    @Test
    fun `screenConversion never screens the party id in place of a name`() {
        val conv = aConversion()
        coEvery { conversionRepository.findById(conv.id) } returns conv
        coEvery { screeningPort.screen(any(), any(), any()) } returns
            ScreeningResult("name", ScreeningRole.DEBTOR, ScreeningMatchStatus.CLEAR, 0.0, null)

        activities.screenConversion(conv.id)

        // A UUID compared against name lists can never hit: the screen would be CLEAR by construction.
        coVerify(exactly = 0) { screeningPort.screen(conv.partyId.toString(), any(), any(), any()) }
    }

    @Test
    fun `screenConversion screens the name party-service holds for the converting party`() {
        val conv = aConversion()
        coEvery { conversionRepository.findById(conv.id) } returns conv
        coEvery { screeningPort.screen(any(), any(), any()) } returns
            ScreeningResult(PARTY_NAME, ScreeningRole.DEBTOR, ScreeningMatchStatus.CLEAR, 0.0, null)

        activities.screenConversion(conv.id)

        coVerify(exactly = 1) { partyNamePort.screeningNames(conv.partyId) }
        coVerify(exactly = 1) {
            screeningPort.screen(PARTY_NAME, ScreeningRole.DEBTOR, "${conv.id}:party", emptyList())
        }
    }

    /**
     * Must-hit / must-clear over a screening double that, like the real lists, knows NAMES. Before
     * the fix the listed party below was CLEAR, because what reached the screen was its UUID.
     */
    @Test
    fun `a party whose name is on the list is blocked, one whose name is not is clear`() {
        listsKnow(LISTED_NAME)
        coEvery { amlCasePort.openCase(any()) } just Runs
        val listed = aConversion()
        val clean = aConversion()
        coEvery { conversionRepository.findById(listed.id) } returns listed
        coEvery { conversionRepository.findById(clean.id) } returns clean
        coEvery { partyNamePort.screeningNames(listed.partyId) } returns listOf(LISTED_NAME)
        coEvery { partyNamePort.screeningNames(clean.partyId) } returns listOf(PARTY_NAME)

        assertThat(activities.screenConversion(listed.id)).isEqualTo(ScreeningDecision.BLOCK)
        assertThat(activities.screenConversion(clean.id)).isEqualTo(ScreeningDecision.CLEAR)
        coVerify(exactly = 1) { amlCasePort.openCase(match { it.alertCode == "SANCTIONS_HIT" }) }
    }

    @Test
    fun `screenConversion holds for REVIEW and never screens when the party name is unavailable`() {
        val conv = aConversion()
        coEvery { conversionRepository.findById(conv.id) } returns conv
        coEvery { partyNamePort.screeningNames(conv.partyId) } throws
            PartyNameUnavailableException(conv.partyId, RuntimeException("party-service down"))
        coEvery { amlCasePort.openCase(any()) } just Runs

        val decision = activities.screenConversion(conv.id)

        // Fail closed, exactly as an unavailable screen: held for review with a case, never CLEAR.
        assertThat(decision).isEqualTo(ScreeningDecision.REVIEW)
        coVerify(exactly = 1) { amlCasePort.openCase(match { it.alertCode == "SCREENING_UNAVAILABLE" }) }
        // …and nothing was screened in the name's place — not the id, not an empty string.
        coVerify(exactly = 0) { screeningPort.screen(any(), any(), any(), any()) }
    }

    /** The case a single screened name lets through: the legal name is clean, the trading name is listed. */
    @Test
    fun `a listed trading name behind a clean legal name blocks the conversion`() {
        listsKnow(LISTED_NAME)
        coEvery { amlCasePort.openCase(any()) } just Runs
        val conv = aConversion()
        coEvery { conversionRepository.findById(conv.id) } returns conv
        coEvery { partyNamePort.screeningNames(conv.partyId) } returns listOf(PARTY_NAME, LISTED_NAME)

        assertThat(activities.screenConversion(conv.id)).isEqualTo(ScreeningDecision.BLOCK)
        // One call, one idempotency key: the legal name as the subject, the trading name as its alias.
        coVerify(exactly = 1) {
            screeningPort.screen(PARTY_NAME, ScreeningRole.DEBTOR, "${conv.id}:party", listOf(LISTED_NAME))
        }
    }

    /** A screening double that, like sanctions-service, searches the name AND every alias. */
    private fun listsKnow(listed: String) {
        coEvery { screeningPort.screen(any(), any(), any(), any()) } answers {
            val hit = (listOf(firstArg<String>()) + arg<List<String>>(3)).contains(listed)
            val status = if (hit) ScreeningMatchStatus.HIT else ScreeningMatchStatus.CLEAR
            ScreeningResult(firstArg(), ScreeningRole.DEBTOR, status, if (hit) 1.0 else 0.0, null)
        }
    }

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

    private companion object {
        const val PARTY_NAME = "Jana Nováková"
        const val LISTED_NAME = "Listed Entity Trading Ltd"
    }
}
