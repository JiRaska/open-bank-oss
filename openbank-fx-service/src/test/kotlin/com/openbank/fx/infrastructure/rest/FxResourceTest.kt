// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.rest

import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.ConvertCommand
import com.openbank.fx.application.port.`in`.FxUseCase
import com.openbank.fx.application.port.`in`.GetRateHistoryQuery
import com.openbank.fx.application.port.`in`.GetRateQuery
import com.openbank.fx.application.port.`in`.ResolvedRate
import com.openbank.fx.domain.model.FxConversion
import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class FxResourceTest {

    private lateinit var fxUseCase: FxUseCase
    private lateinit var cnbIngestion: CnbRateIngestionUseCase
    private lateinit var resource: FxResource

    @BeforeEach
    fun setUp() {
        fxUseCase = mockk()
        cnbIngestion = mockk()
        resource = FxResource(fxUseCase, cnbIngestion)
    }

    private fun rate() = FxRate(
        id = UUID.randomUUID(),
        baseCurrency = "EUR",
        quoteCurrency = "CZK",
        bidRate = BigDecimal("24.90"),
        askRate = BigDecimal("25.10"),
        rateType = RateType.SPOT,
        source = RateSource.INTERNAL,
        validFrom = Instant.parse("2026-01-01T00:00:00Z"),
        validTo = Instant.parse("2026-12-31T23:59:59Z"),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun conversion() = FxConversion(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-1",
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

    @Test
    fun `getRates returns 200 with all current rates from the use case`(): Unit = runBlocking {
        val rates = listOf(rate())
        coEvery { fxUseCase.getAllRates() } returns rates

        val resp = resource.getRates()

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity).isEqualTo(rates)
    }

    @Test
    fun `getRate with no source returns the internal spot rate`(): Unit = runBlocking {
        val stored = rate()
        coEvery { fxUseCase.getRate(GetRateQuery("EUR", "CZK")) } returns ResolvedRate(stored, derivedFrom = null)

        val resp = resource.getRate(base = "eur", quote = "czk", source = null, asOfStr = null)

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity).isEqualTo(FxRateResponse.of(stored, derivedFrom = null))
        coVerify(exactly = 0) { cnbIngestion.getCnbRate(any(), any(), any()) }
    }

    @Test
    fun `getRate marks an inverted pair as derived - id null, source id in derivedFrom (#3374)`(): Unit = runBlocking {
        val stored = rate() // EUR/CZK — the only direction the ČNB source ever stores
        coEvery { fxUseCase.getRate(GetRateQuery("CZK", "EUR")) } returns
            ResolvedRate(stored.inverted(), derivedFrom = stored.id)

        val resp = resource.getRate(base = "czk", quote = "eur", source = null, asOfStr = null)

        assertThat(resp.status).isEqualTo(200)
        val body = resp.entity as FxRateResponse
        assertThat(body.id).isNull()
        assertThat(body.derivedFrom).isEqualTo(stored.id)
        assertThat(body.pair).isEqualTo("CZK/EUR")
        // The sides swap on inversion: bid = 1 / source ask, ask = 1 / source bid.
        assertThat(
            body.bidRate,
        ).isEqualByComparingTo(BigDecimal.ONE.divide(stored.askRate, 8, java.math.RoundingMode.HALF_UP))
        assertThat(
            body.askRate,
        ).isEqualByComparingTo(BigDecimal.ONE.divide(stored.bidRate, 8, java.math.RoundingMode.HALF_UP))
    }

    @Test
    fun `getRate with source=CNB delegates to the ČNB ingestion use case instead`(): Unit = runBlocking {
        val stored = rate()
        coEvery { cnbIngestion.getCnbRate("EUR", "CZK", null) } returns stored

        val resp = resource.getRate(base = "eur", quote = "czk", source = "cnb", asOfStr = null)

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity).isEqualTo(FxRateResponse.of(stored, derivedFrom = null))
        coVerify(exactly = 0) { fxUseCase.getRate(any()) }
    }

    // ── #3921 step 3: ?asOf pins the fixing's business day ───────────────────────────────────

    @Test
    fun `getRate with source=CNB and asOf resolves the fixing in effect on that day`(): Unit = runBlocking {
        val stored = rate()
        val backfill = LocalDate.of(2026, 5, 27)
        coEvery { cnbIngestion.getCnbRate("EUR", "CZK", backfill) } returns stored

        val resp = resource.getRate(base = "eur", quote = "czk", source = "cnb", asOfStr = "2026-05-27")

        assertThat(resp.status).isEqualTo(200)
        // The DATE reaches the use case. A dropped date parameter is invisible from the response —
        // it answers 200 with a rate either way — so the verify IS the assertion here.
        coVerify(exactly = 1) { cnbIngestion.getCnbRate("EUR", "CZK", backfill) }
        coVerify(exactly = 0) { cnbIngestion.getCnbRate("EUR", "CZK", null) }
    }

    @Test
    fun `getRate answers 404 for a day no fixing was in effect, never the newest one`(): Unit = runBlocking {
        val backfill = LocalDate.of(2020, 1, 1)
        coEvery { cnbIngestion.getCnbRate("EUR", "CZK", backfill) } returns null

        val resp = resource.getRate(base = "eur", quote = "czk", source = "cnb", asOfStr = "2020-01-01")

        // Falling back to the latest fixing is exactly the defect (#3921 item 3): a backfill would
        // then report success having marked an old day at today's rate.
        assertThat(resp.status).isEqualTo(404)
    }

    @Test
    fun `getRate rejects asOf without source=CNB rather than ignoring it`(): Unit = runBlocking {
        val resp = resource.getRate(base = "eur", quote = "czk", source = null, asOfStr = "2026-05-27")

        assertThat(resp.status).isEqualTo(400)
        coVerify(exactly = 0) { fxUseCase.getRate(any()) }
    }

    @Test
    fun `getRate rejects an unparseable asOf with 400, not 500`(): Unit = runBlocking {
        val resp = resource.getRate(base = "eur", quote = "czk", source = "cnb", asOfStr = "30-05-2026")

        assertThat(resp.status).isEqualTo(400)
        coVerify(exactly = 0) { cnbIngestion.getCnbRate(any(), any(), any()) }
    }

    @Test
    fun `getRate returns 404 when no rate is stored for the pair`(): Unit = runBlocking {
        coEvery { fxUseCase.getRate(GetRateQuery("USD", "CZK")) } returns null

        val resp = resource.getRate(base = "usd", quote = "czk", source = null, asOfStr = null)

        assertThat(resp.status).isEqualTo(404)
        // The 404 message echoes the raw path params, not the uppercased lookup key (FxResource.getRate).
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.entity as Map<String, String>)["error"]).contains("usd/czk")
    }

    @Test
    fun `convert requires a non-blank Idempotency-Key header`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking {
                resource.convert(
                    ConvertRequest(
                        partyId = UUID.randomUUID(),
                        accountId = null,
                        partyName = "Alice",
                        fromCurrency = "EUR",
                        toCurrency = "CZK",
                        fromAmountMinorUnits = 1000L,
                    ),
                    key = "  ",
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `convert maps the request onto a ConvertCommand and returns 201 with a Location header`(): Unit = runBlocking {
        val result = conversion()
        val cmd = slot<ConvertCommand>()
        coEvery { fxUseCase.convert(capture(cmd)) } returns result

        val req = ConvertRequest(
            partyId = result.partyId,
            accountId = result.accountId,
            partyName = "Alice Example",
            fromCurrency = "EUR",
            toCurrency = "CZK",
            fromAmountMinorUnits = 10_000L,
        )
        val resp = resource.convert(req, key = "idem-key-1")

        assertThat(resp.status).isEqualTo(201)
        assertThat(resp.entity).isEqualTo(result)
        assertThat(resp.location.toString()).isEqualTo("/api/v1/fx/conversions/${result.id}")
        assertThat(cmd.captured.idempotencyKey).isEqualTo("idem-key-1")
        assertThat(cmd.captured.partyId).isEqualTo(result.partyId)
        assertThat(cmd.captured.fromAmountMinorUnits).isEqualTo(10_000L)
    }

    @Test
    fun `getConversion returns 200 when the conversion exists`(): Unit = runBlocking {
        val result = conversion()
        coEvery { fxUseCase.getConversion(result.id) } returns result

        val resp = resource.getConversion(result.id)

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity).isEqualTo(result)
    }

    @Test
    fun `getConversion returns 404 when it does not exist`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { fxUseCase.getConversion(id) } returns null

        val resp = resource.getConversion(id)

        assertThat(resp.status).isEqualTo(404)
    }

    @Test
    fun `getRateHistory returns 400 for unknown source`(): Unit = runBlocking {
        val resp: Response = resource.getRateHistory(
            base = "EUR",
            quote = "CZK",
            sourceStr = "RUBBISH",
            fromStr = null,
            toStr = null,
            limit = null,
            offset = null,
        )
        assertThat(resp.status).isEqualTo(400)
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.entity as Map<String, String>)["error"]).contains("Unknown source")
    }

    @Test
    fun `getRateHistory returns 400 for malformed from instant`(): Unit = runBlocking {
        val resp: Response = resource.getRateHistory(
            base = "EUR",
            quote = "CZK",
            sourceStr = null,
            fromStr = "not-a-date",
            toStr = null,
            limit = null,
            offset = null,
        )
        assertThat(resp.status).isEqualTo(400)
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.entity as Map<String, String>)["error"]).contains("Invalid 'from' instant")
    }

    @Test
    fun `getRateHistory returns 400 for malformed to instant`(): Unit = runBlocking {
        val resp: Response = resource.getRateHistory(
            base = "EUR",
            quote = "CZK",
            sourceStr = null,
            fromStr = null,
            toStr = "bad",
            limit = null,
            offset = null,
        )
        assertThat(resp.status).isEqualTo(400)
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.entity as Map<String, String>)["error"]).contains("Invalid 'to' instant")
    }

    @Test
    fun `getRateHistory returns 400 when from is after to`(): Unit = runBlocking {
        val resp: Response = resource.getRateHistory(
            base = "EUR",
            quote = "CZK",
            sourceStr = null,
            fromStr = "2026-06-14T12:00:00Z",
            toStr = "2026-01-01T00:00:00Z",
            limit = null,
            offset = null,
        )
        assertThat(resp.status).isEqualTo(400)
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.entity as Map<String, String>)["error"]).contains("'from' must be before 'to'")
    }

    @Test
    fun `getRateHistory returns 200 with empty list when use case returns nothing`(): Unit = runBlocking {
        coEvery { fxUseCase.getRateHistory(any()) } returns emptyList()

        val resp: Response = resource.getRateHistory(
            base = "EUR",
            quote = "CZK",
            sourceStr = null,
            fromStr = null,
            toStr = null,
            limit = null,
            offset = null,
        )
        assertThat(resp.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        assertThat(resp.entity as List<*>).isEmpty()
    }

    @Test
    fun `getRateHistory normalises pair to uppercase and caps limit at 365`(): Unit = runBlocking {
        val captured = slot<GetRateHistoryQuery>()
        coEvery { fxUseCase.getRateHistory(capture(captured)) } returns emptyList()

        resource.getRateHistory(
            base = "eur",
            quote = "czk",
            sourceStr = null,
            fromStr = null,
            toStr = null,
            limit = 999,
            offset = null,
        )

        assertThat(captured.isCaptured).isTrue()
        assertThat(captured.captured.baseCurrency).isEqualTo("EUR")
        assertThat(captured.captured.quoteCurrency).isEqualTo("CZK")
        assertThat(captured.captured.limit).isEqualTo(365)
    }
}
