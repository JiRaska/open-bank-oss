// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.usecase

import com.openbank.fx.application.port.`in`.ConvertCommand
import com.openbank.fx.application.port.`in`.GetRateHistoryQuery
import com.openbank.fx.application.port.`in`.GetRateQuery
import com.openbank.fx.application.port.out.AmlCasePort
import com.openbank.fx.application.port.out.AmlCaseRiskLevel
import com.openbank.fx.application.port.out.FraudScoreOutcome
import com.openbank.fx.application.port.out.FraudScoringPort
import com.openbank.fx.application.port.out.FraudVerdict
import com.openbank.fx.application.port.out.FxConversionRepository
import com.openbank.fx.application.port.out.FxEventPublisher
import com.openbank.fx.application.port.out.FxRateRepository
import com.openbank.fx.application.port.out.OpenAmlCaseCommand
import com.openbank.fx.application.port.out.SanctionsScreeningPort
import com.openbank.fx.application.port.out.ScreeningUnavailableException
import com.openbank.fx.domain.model.FxConversion
import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningResult
import com.openbank.fx.domain.screening.ScreeningRole
import com.openbank.libs.observability.DomainMetrics
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FxServiceTest {

    private lateinit var rateRepo: FxRateRepository
    private lateinit var convRepo: FxConversionRepository
    private lateinit var publisher: FxEventPublisher
    private lateinit var screeningPort: SanctionsScreeningPort
    private lateinit var amlCasePort: AmlCasePort
    private lateinit var metrics: DomainMetrics
    private lateinit var fraudScoringPort: FraudScoringPort
    private lateinit var service: FxService

    // Must sit inside the fxRate() validity window (validFrom 2026-01-01 .. validTo 2026-12-31):
    // convert() now reads `Instant.now(clock)` (ADR-0100) for the rate.isValid() gate, so a clock
    // outside that window makes every conversion fail with "FX rate expired".
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        rateRepo = mockk()
        convRepo = mockk()
        publisher = mockk(relaxed = true)
        screeningPort = mockk()
        amlCasePort = mockk()
        metrics = mockk(relaxed = true)
        fraudScoringPort = mockk()
        // Fraud scoring is SHADOW (ADR-0084): default to ALLOW; never affects conversion outcome.
        coEvery { fraudScoringPort.score(any()) } returns FraudScoreOutcome(FraudVerdict.ALLOW, 0, "v0", emptyList())
        service = FxService(rateRepo, convRepo, publisher, screeningPort, amlCasePort, metrics, fraudScoringPort, clock)

        // By default: not idempotent-replayed, persistence echoes the saved row, AML opens cleanly.
        coEvery { convRepo.findByIdempotencyKey(any()) } returns null
        coEvery { convRepo.save(any()) } answers { firstArg() }
        coEvery { amlCasePort.openCase(any()) } just Runs
    }

    private fun clear() {
        coEvery { screeningPort.screen(any(), any(), any()) } answers {
            ScreeningResult(firstArg(), secondArg(), ScreeningMatchStatus.CLEAR, 0.0, null)
        }
    }

    @Test
    fun `convert is idempotent`() = runBlocking<Unit> {
        val command = convertCommand()
        val existing = conversion(idempotencyKey = command.idempotencyKey)
        coEvery { convRepo.findByIdempotencyKey(command.idempotencyKey) } returns existing

        val result = service.convert(command)

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 0) { rateRepo.findLatest(any(), any(), any()) }
        coVerify(exactly = 0) { convRepo.save(any()) }
        coVerify(exactly = 0) { screeningPort.screen(any(), any(), any()) }
        coVerify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `convert throws error when no FX rate available`() = runBlocking<Unit> {
        val command = convertCommand()
        coEvery { rateRepo.findLatest(command.fromCurrency, command.toCurrency, RateType.SPOT) } returns null

        val ex = assertThrows<IllegalStateException> { runBlocking { service.convert(command) } }

        assertThat(ex).hasMessage("No FX rate available for EUR/CZK")
    }

    @Test
    fun `convert throws when rate is expired`() = runBlocking<Unit> {
        val command = convertCommand()
        val expiredRate = fxRate(validTo = Instant.parse("2024-01-01T00:00:00Z"))
        coEvery { rateRepo.findLatest(command.fromCurrency, command.toCurrency, RateType.SPOT) } returns expiredRate

        val ex = assertThrows<IllegalArgumentException> { runBlocking { service.convert(command) } }

        assertThat(ex).hasMessage("FX rate expired for EUR/CZK")
    }

    @Test
    fun `clean party settles and publishes`() = runBlocking<Unit> {
        val command = convertCommand()
        coEvery { rateRepo.findLatest(any(), any(), any()) } returns fxRate()
        clear()

        val saved = slot<FxConversion>()
        coEvery { convRepo.save(capture(saved)) } answers { firstArg() }

        val result = service.convert(command)

        assertThat(result.status).isEqualTo(FxConversionStatus.SETTLED)
        assertThat(result.settledAt).isNotNull()
        coVerify(exactly = 1) { screeningPort.screen(command.partyName, ScreeningRole.DEBTOR, any()) }
        coVerify(exactly = 1) { publisher.publish(any()) }
        coVerify(exactly = 0) { amlCasePort.openCase(any()) }
    }

    @Test
    fun `sanctions hit fails the conversion and opens a CRITICAL case`() = runBlocking<Unit> {
        val command = convertCommand()
        coEvery { rateRepo.findLatest(any(), any(), any()) } returns fxRate()
        coEvery { screeningPort.screen(any(), any(), any()) } returns
            ScreeningResult(command.partyName, ScreeningRole.DEBTOR, ScreeningMatchStatus.HIT, 0.97, "OFAC SDN")

        val case = slot<OpenAmlCaseCommand>()
        coEvery { amlCasePort.openCase(capture(case)) } just Runs

        val result = service.convert(command)

        assertThat(result.status).isEqualTo(FxConversionStatus.FAILED)
        assertThat(result.settledAt).isNull()
        assertThat(case.captured.riskLevel).isEqualTo(AmlCaseRiskLevel.CRITICAL)
        assertThat(case.captured.alertCode).isEqualTo("SANCTIONS_HIT")
        assertThat(case.captured.matchedEntity).isEqualTo("OFAC SDN")
        coVerify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `potential hit below threshold holds in PENDING with a HIGH case`() = runBlocking<Unit> {
        val command = convertCommand()
        coEvery { rateRepo.findLatest(any(), any(), any()) } returns fxRate()
        coEvery { screeningPort.screen(any(), any(), any()) } returns
            ScreeningResult(command.partyName, ScreeningRole.DEBTOR, ScreeningMatchStatus.POTENTIAL_HIT, 0.50, null)

        val case = slot<OpenAmlCaseCommand>()
        coEvery { amlCasePort.openCase(capture(case)) } just Runs

        val result = service.convert(command)

        assertThat(result.status).isEqualTo(FxConversionStatus.PENDING)
        assertThat(result.settledAt).isNull()
        assertThat(case.captured.riskLevel).isEqualTo(AmlCaseRiskLevel.HIGH)
        assertThat(case.captured.alertCode).isEqualTo("AML_HOLD")
        coVerify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `screening unavailable fails closed holding in PENDING with a MEDIUM case`() = runBlocking<Unit> {
        val command = convertCommand()
        coEvery { rateRepo.findLatest(any(), any(), any()) } returns fxRate()
        coEvery { screeningPort.screen(any(), any(), any()) } throws
            ScreeningUnavailableException(RuntimeException("connection refused"))

        val case = slot<OpenAmlCaseCommand>()
        coEvery { amlCasePort.openCase(capture(case)) } just Runs

        val result = service.convert(command)

        assertThat(result.status).isEqualTo(FxConversionStatus.PENDING)
        assertThat(result.settledAt).isNull()
        assertThat(case.captured.riskLevel).isEqualTo(AmlCaseRiskLevel.MEDIUM)
        assertThat(case.captured.alertCode).isEqualTo("SCREENING_UNAVAILABLE")
        coVerify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `AML case-store outage does not flip a settled verdict`() = runBlocking<Unit> {
        val command = convertCommand()
        coEvery { rateRepo.findLatest(any(), any(), any()) } returns fxRate()
        coEvery { screeningPort.screen(any(), any(), any()) } returns
            ScreeningResult(command.partyName, ScreeningRole.DEBTOR, ScreeningMatchStatus.POTENTIAL_HIT, 0.50, null)
        coEvery { amlCasePort.openCase(any()) } throws RuntimeException("aml-service down")

        val result = service.convert(command)

        assertThat(result.status).isEqualTo(FxConversionStatus.PENDING)
    }

    @Test
    fun `getAllRates returns only rates from repository findAll`() = runBlocking<Unit> {
        val rates = listOf(fxRate())
        coEvery { rateRepo.findAll() } returns rates

        val result = service.getAllRates()

        assertThat(result).isEqualTo(rates)
        coVerify(exactly = 1) { rateRepo.findAll() }
    }

    @Test
    fun `getRate delegates to repository`() = runBlocking<Unit> {
        val query = GetRateQuery("EUR", "CZK", RateType.SPOT)
        val rate = fxRate()
        coEvery { rateRepo.findLatest(query.baseCurrency, query.quoteCurrency, query.rateType) } returns rate

        val result = service.getRate(query)

        assertThat(result).isEqualTo(rate)
        coVerify(exactly = 1) { rateRepo.findLatest(query.baseCurrency, query.quoteCurrency, query.rateType) }
    }

    @Test
    fun `getRateHistory delegates to repository with normalised uppercase pair`() = runBlocking<Unit> {
        val rates = listOf(fxRate())
        coEvery { rateRepo.findHistory("EUR", "CZK", RateSource.INTERNAL, null, null, 50, 0) } returns rates

        val result = service.getRateHistory(
            GetRateHistoryQuery("eur", "czk", source = RateSource.INTERNAL, limit = 50, offset = 0),
        )

        assertThat(result).isEqualTo(rates)
        coVerify(exactly = 1) { rateRepo.findHistory("EUR", "CZK", RateSource.INTERNAL, null, null, 50, 0) }
    }

    @Test
    fun `getRateHistory returns empty list when no data stored`() = runBlocking<Unit> {
        coEvery { rateRepo.findHistory("USD", "CZK", null, null, null, 100, 0) } returns emptyList()

        val result = service.getRateHistory(GetRateHistoryQuery("USD", "CZK"))

        assertThat(result).isEmpty()
    }

    @Test
    fun `fraud shadow verdict is observed but never enforced during conversion`(): Unit = runBlocking {
        val command = convertCommand()
        clear()
        coEvery { fraudScoringPort.score(any()) } returns
            FraudScoreOutcome(FraudVerdict.DECLINE, 99, "v0", listOf("velocity-cap"))
        val rate = fxRate()
        coEvery { rateRepo.findLatest(any(), any(), any()) } returns rate
        coEvery { convRepo.save(any()) } answers { firstArg() }

        val result = service.convert(command)

        assertThat(result.status).isEqualTo(FxConversionStatus.SETTLED)
        coVerify(exactly = 1) { fraudScoringPort.score(any()) }
    }

    private fun convertCommand() = ConvertCommand(
        idempotencyKey = "idem-1",
        partyId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        fromCurrency = "EUR",
        toCurrency = "CZK",
        fromAmountMinorUnits = 10_000L,
        partyName = "Alice Example",
    )

    private fun fxRate(validTo: Instant = Instant.parse("2026-12-31T23:59:59Z")) = FxRate(
        id = UUID.randomUUID(),
        baseCurrency = "EUR",
        quoteCurrency = "CZK",
        bidRate = BigDecimal("24.90"),
        askRate = BigDecimal("25.10"),
        rateType = RateType.SPOT,
        source = RateSource.INTERNAL,
        validFrom = Instant.parse("2026-01-01T00:00:00Z"),
        validTo = validTo,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun conversion(idempotencyKey: String) = FxConversion(
        id = UUID.randomUUID(),
        idempotencyKey = idempotencyKey,
        partyId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        fromCurrency = "EUR",
        toCurrency = "CZK",
        fromAmountMinorUnits = 10_000L,
        toAmountMinorUnits = 251_000L,
        appliedRate = BigDecimal("25.10"),
        feeMinorUnits = 50L,
        rateId = UUID.randomUUID(),
        status = FxConversionStatus.SETTLED,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        settledAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
