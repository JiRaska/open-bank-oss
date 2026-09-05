// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.openbank.fx.application.port.`in`.ConvertCommand
import com.openbank.fx.application.port.`in`.GetRateHistoryQuery
import com.openbank.fx.application.port.`in`.GetRateQuery
import com.openbank.fx.application.port.`in`.ResolvedRate
import com.openbank.fx.application.port.out.AmlCasePort
import com.openbank.fx.application.port.out.AmlCaseRiskLevel
import com.openbank.fx.application.port.out.FraudScoreOutcome
import com.openbank.fx.application.port.out.FraudScoringPort
import com.openbank.fx.application.port.out.FraudVerdict
import com.openbank.fx.application.port.out.FxConversionRepository
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
import com.openbank.libs.persistence.outbox.OutboxMessage
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
    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
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
        screeningPort = mockk()
        amlCasePort = mockk()
        metrics = mockk(relaxed = true)
        fraudScoringPort = mockk()
        // Fraud scoring is SHADOW (ADR-0084): default to ALLOW; never affects conversion outcome.
        coEvery { fraudScoringPort.score(any()) } returns FraudScoreOutcome(FraudVerdict.ALLOW, 0, "v0", emptyList())
        service =
            FxService(rateRepo, convRepo, objectMapper, screeningPort, amlCasePort, metrics, fraudScoringPort, clock)

        // By default: not idempotent-replayed, persistence echoes the saved row, AML opens cleanly.
        coEvery { convRepo.findByIdempotencyKey(any()) } returns null
        coEvery { convRepo.save(any()) } answers { firstArg() }
        coEvery { convRepo.saveWithOutbox(any(), any()) } answers { firstArg() }
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
        coVerify(exactly = 0) { convRepo.saveWithOutbox(any(), any()) }
    }

    @Test
    fun `convert throws error when no FX rate available in either direction`() = runBlocking<Unit> {
        val command = convertCommand()
        // Both directions must be absent now: a pair quoted the other way round is priceable.
        coEvery { rateRepo.findLatest(any(), any(), RateType.SPOT) } returns null

        val ex = assertThrows<IllegalStateException> { runBlocking { service.convert(command) } }

        assertThat(ex).hasMessage("No FX rate available for EUR/CZK")
    }

    // --- pricing from the reverse quote ------------------------------------------------------
    // The ČNB fixing publishes FOREIGN→CZK only, so every stored pair is X/CZK. Before this,
    // CZK→EUR had no answer at all: not a transient failure but every customer-initiated
    // CZK→foreign exchange, permanently, surfacing in the app as HTTP 502.

    @Test
    fun `getRate answers from the reverse quote when only that direction is stored`() = runBlocking<Unit> {
        val stored = fxRate()
        coEvery { rateRepo.findLatest("CZK", "EUR", RateType.SPOT) } returns null
        coEvery { rateRepo.findLatest("EUR", "CZK", RateType.SPOT) } returns stored

        val resolved = service.getRate(GetRateQuery("CZK", "EUR", RateType.SPOT))

        assertThat(resolved).isNotNull
        val rate = resolved!!.rate
        assertThat(rate.baseCurrency).isEqualTo("CZK")
        assertThat(rate.quoteCurrency).isEqualTo("EUR")
        // 1/25.10 (the ask side), not 1/24.90.
        assertThat(rate.bidRate).isEqualByComparingTo("0.03984064")
        // #3374: the resolution names the stored row it was derived from.
        assertThat(resolved.derivedFrom).isEqualTo(stored.id)
        assertThat(rate.id).isEqualTo(stored.id)
    }

    @Test
    fun `a directly stored pair is never overridden by an inverse`() = runBlocking<Unit> {
        coEvery { rateRepo.findLatest("EUR", "CZK", RateType.SPOT) } returns fxRate()

        val resolved = service.getRate(GetRateQuery("EUR", "CZK", RateType.SPOT))

        assertThat(resolved!!.rate.bidRate).isEqualByComparingTo("24.90")
        assertThat(resolved.derivedFrom).isNull()
        coVerify(exactly = 0) { rateRepo.findLatest("CZK", "EUR", RateType.SPOT) }
    }

    @Test
    fun `convert prices a reverse-quoted pair instead of refusing it`() = runBlocking<Unit> {
        val command = convertCommand().copy(fromCurrency = "CZK", toCurrency = "EUR")
        coEvery { rateRepo.findLatest("CZK", "EUR", RateType.SPOT) } returns null
        coEvery { rateRepo.findLatest("EUR", "CZK", RateType.SPOT) } returns fxRate()
        coEvery { convRepo.saveWithOutbox(any(), any()) } answers { firstArg() }
        clear()

        val conv = service.convert(command)

        // convert() charges the ASK side, and after inversion the ask is 1/24.90 — the customer
        // buying EUR pays the bank's selling price, exactly as they would on a directly quoted
        // pair. (The inverted BID, 1/25.10, is what a customer SELLING EUR would receive.)
        assertThat(conv.appliedRate).isEqualByComparingTo("0.04016064")
    }

    // --- fx_conversions.rate_id must name a STORED row (#3374) --------------------------------
    //
    // `fx_conversions.rate_id` is `NOT NULL REFERENCES fx_rates(id)`. A CZK->foreign pair has no
    // stored row of its own — the CNB fixing publishes FOREIGN->CZK only — so it is answered by
    // inverting the stored direction, and whatever id that derived quote carries is what reaches
    // the column. If it is ever an id with no row, EVERY CZK->foreign conversion fails the insert
    // on a foreign-key violation, and the audit record cites an id nothing can resolve.
    //
    // Today the invariant holds by construction, because `inverted()` carries the source id over.
    // That is precisely why it is worth pinning: #3374 is a live proposal to give a derived quote
    // its own identity (#3594 minted a deterministic derived id, #3741 nulls it in the response),
    // and the money-path property has to survive whichever shape lands. These assert the property
    // itself — the id written is the STORED row's — not any particular derivation scheme, so they
    // stay meaningful under both designs and fail the moment a derived id reaches the column.

    @Test
    fun `a conversion on a derived quote records the stored row id`() = runBlocking<Unit> {
        val stored = fxRate()
        coEvery { rateRepo.findLatest("CZK", "EUR", RateType.SPOT) } returns null
        coEvery { rateRepo.findLatest("EUR", "CZK", RateType.SPOT) } returns stored
        coEvery { convRepo.saveWithOutbox(any(), any()) } answers { firstArg() }
        clear()

        val conv = service.convert(convertCommand().copy(fromCurrency = "CZK", toCurrency = "EUR"))

        assertThat(conv.rateId).isEqualTo(stored.id)
    }

    @Test
    fun `a conversion on a stored quote records that row's id`() = runBlocking<Unit> {
        val stored = fxRate()
        coEvery { rateRepo.findLatest("EUR", "CZK", RateType.SPOT) } returns stored
        coEvery { convRepo.saveWithOutbox(any(), any()) } answers { firstArg() }
        clear()

        val conv = service.convert(convertCommand().copy(fromCurrency = "EUR", toCurrency = "CZK"))

        assertThat(conv.rateId).isEqualTo(stored.id)
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

        val outboxMessage = slot<OutboxMessage>()
        coEvery { convRepo.saveWithOutbox(any(), capture(outboxMessage)) } answers { firstArg() }

        val result = service.convert(command)

        assertThat(result.status).isEqualTo(FxConversionStatus.SETTLED)
        assertThat(result.settledAt).isNotNull()
        coVerify(exactly = 1) { screeningPort.screen(command.partyName, ScreeningRole.DEBTOR, any()) }
        coVerify(exactly = 1) { convRepo.saveWithOutbox(any(), any()) }
        coVerify(exactly = 0) { amlCasePort.openCase(any()) }
        // #1033 regression: the outbox row must actually carry a serialized FxConversionExecuted
        // payload, not an empty/placeholder body — this is exactly what the stub publisher used to
        // silently drop.
        assertThat(outboxMessage.captured.eventType).isEqualTo("fx.conversion.executed.v1")
        assertThat(outboxMessage.captured.aggregateId).isEqualTo(result.id)
        assertThat(outboxMessage.captured.payload).contains(result.id.toString()).contains("\"toAmount\"")
        // Issue #3994/#5256: the wire payload actually reaching Kafka carries sourceService — a
        // domain-level assertion on FxConversionExecuted alone cannot prove that.
        assertThat(outboxMessage.captured.payload).contains("\"sourceService\":\"fx-service\"")
    }

    @Test
    fun `conversion applies the ask rate and rounds the converted amount half-up`() = runBlocking<Unit> {
        val command = convertCommand(fromAmountMinorUnits = 10_000L) // 100.00 EUR
        coEvery { rateRepo.findLatest(any(), any(), any()) } returns fxRate() // ask = 25.10
        clear()

        val result = service.convert(command)

        // 10_000 * 25.10 = 251_000.0 exactly -> no rounding ambiguity
        assertThat(result.toAmountMinorUnits).isEqualTo(251_000L)
        assertThat(result.appliedRate).isEqualByComparingTo("25.10")
    }

    @Test
    fun `conversion fee is 0_5 percent of the source amount rounded half-up`() = runBlocking<Unit> {
        val command = convertCommand(fromAmountMinorUnits = 999L) // fee = 4.995 -> rounds to 5
        coEvery { rateRepo.findLatest(any(), any(), any()) } returns fxRate()
        clear()

        val result = service.convert(command)

        assertThat(result.feeMinorUnits).isEqualTo(5L)
    }

    @Test
    fun `a tiny source amount still produces a non-negative rounded result`() = runBlocking<Unit> {
        val command = convertCommand(fromAmountMinorUnits = 1L) // 0.01 EUR
        coEvery { rateRepo.findLatest(any(), any(), any()) } returns fxRate()
        clear()

        val result = service.convert(command)

        // 1 * 25.10 = 25.1 -> HALF_UP to 25
        assertThat(result.toAmountMinorUnits).isEqualTo(25L)
        assertThat(result.feeMinorUnits).isZero() // 1 * 0.005 = 0.005 -> HALF_UP to 0
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
        coVerify(exactly = 0) { convRepo.saveWithOutbox(any(), any()) }
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
        coVerify(exactly = 0) { convRepo.saveWithOutbox(any(), any()) }
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
        coVerify(exactly = 0) { convRepo.saveWithOutbox(any(), any()) }
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

        assertThat(result).isEqualTo(ResolvedRate(rate, derivedFrom = null))
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
        coEvery { rateRepo.findHistory("CZK", "USD", null, null, null, 100, 0) } returns emptyList()

        val result = service.getRateHistory(GetRateHistoryQuery("USD", "CZK"))

        assertThat(result).isEmpty()
    }

    @Test
    fun `getRateHistory inverts reverse history when direct pair is absent`() = runBlocking<Unit> {
        val stored = fxRate().copy(
            baseCurrency = "EUR",
            quoteCurrency = "CZK",
            bidRate = java.math.BigDecimal("24.00"),
            askRate = java.math.BigDecimal("25.00"),
        )
        coEvery { rateRepo.findHistory("CZK", "EUR", null, null, null, 100, 0) } returns emptyList()
        coEvery { rateRepo.findHistory("EUR", "CZK", null, null, null, 100, 0) } returns listOf(stored)

        val result = service.getRateHistory(GetRateHistoryQuery("CZK", "EUR"))

        assertThat(result).hasSize(1)
        assertThat(result.single().baseCurrency).isEqualTo("CZK")
        assertThat(result.single().quoteCurrency).isEqualTo("EUR")
        assertThat(result.single().bidRate).isEqualByComparingTo("0.04000000")
        assertThat(result.single().askRate).isEqualByComparingTo("0.04166667")
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

    private fun convertCommand(fromAmountMinorUnits: Long = 10_000L) = ConvertCommand(
        idempotencyKey = "idem-1",
        partyId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        fromCurrency = "EUR",
        toCurrency = "CZK",
        fromAmountMinorUnits = fromAmountMinorUnits,
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
