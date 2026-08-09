// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.usecase

import com.openbank.fx.application.port.`in`.IngestCnbFixingCommand
import com.openbank.fx.application.port.out.CnbRateProvider
import com.openbank.fx.application.port.out.FxRateRepository
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CnbRateIngestionServiceTest {

    private val sample = """
        30.05.2026 #104
        země|měna|množství|kód|kurz
        EMU|euro|1|EUR|25,145
        Japonsko|jen|100|JPY|14,621
        USA|dolar|1|USD|22,310
        Velká Británie|libra|1|GBP|29,840
    """.trimIndent()

    private val prague = ZoneId.of("Europe/Prague")
    private val expectedValidFrom = LocalDate.of(2026, 5, 30).atStartOfDay(prague).toInstant()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-30T12:00:00Z"), ZoneOffset.UTC)

    private fun service(repo: FxRateRepository, provider: CnbRateProvider) =
        CnbRateIngestionService(provider, repo, "EUR,USD,GBP", clock)

    @Test
    fun `ingests only configured currencies as CNB CZK rates with bid equal ask equal per-unit`() = runBlocking<Unit> {
        val repo = mockk<FxRateRepository>()
        val provider = mockk<CnbRateProvider>()
        coEvery { provider.fetchFixing(any()) } returns sample
        coEvery { repo.findBySourceAndValidFrom(any(), any(), any(), any()) } returns null
        val saved = mutableListOf<FxRate>()
        coEvery { repo.save(capture(slot<FxRate>())) } answers { firstArg<FxRate>().also { saved += it } }

        val result = service(repo, provider).ingest(IngestCnbFixingCommand(LocalDate.of(2026, 5, 30)))

        // JPY is not configured → skipped; EUR/USD/GBP stored
        assertThat(result.ingested).isEqualTo(3)
        assertThat(result.skipped).isEqualTo(0)
        assertThat(result.currencies).containsExactlyInAnyOrder("EUR", "USD", "GBP")
        assertThat(result.date).isEqualTo(LocalDate.of(2026, 5, 30))
        assertThat(result.sequence).isEqualTo(104)

        val eur = saved.first { it.baseCurrency == "EUR" }
        assertThat(eur.quoteCurrency).isEqualTo("CZK")
        assertThat(eur.source).isEqualTo(RateSource.CNB)
        assertThat(eur.rateType).isEqualTo(RateType.INDICATIVE)
        assertThat(eur.bidRate).isEqualByComparingTo("25.145")
        assertThat(eur.askRate).isEqualByComparingTo("25.145")
        assertThat(eur.validFrom).isEqualTo(expectedValidFrom)
    }

    @Test
    fun `is idempotent — already-stored fixing for the day is skipped, not re-saved`() = runBlocking<Unit> {
        val repo = mockk<FxRateRepository>()
        val provider = mockk<CnbRateProvider>()
        coEvery { provider.fetchFixing(any()) } returns sample
        val existing = FxRate(
            id = java.util.UUID.randomUUID(), baseCurrency = "EUR", quoteCurrency = "CZK",
            bidRate = java.math.BigDecimal("25.145"), askRate = java.math.BigDecimal("25.145"),
            rateType = RateType.INDICATIVE, source = RateSource.CNB,
            validFrom = expectedValidFrom, validTo = expectedValidFrom, createdAt = Instant.now(),
        )
        // EUR already present, USD/GBP not
        coEvery { repo.findBySourceAndValidFrom("EUR", "CZK", RateSource.CNB, expectedValidFrom) } returns existing
        coEvery { repo.findBySourceAndValidFrom("USD", "CZK", RateSource.CNB, expectedValidFrom) } returns null
        coEvery { repo.findBySourceAndValidFrom("GBP", "CZK", RateSource.CNB, expectedValidFrom) } returns null
        coEvery { repo.save(any()) } answers { firstArg() }

        val result = service(repo, provider).ingest(IngestCnbFixingCommand(LocalDate.of(2026, 5, 30)))

        assertThat(result.ingested).isEqualTo(2)
        assertThat(result.skipped).isEqualTo(1)
        coVerify(exactly = 0) { repo.save(match { it.baseCurrency == "EUR" }) }
    }

    // ── #3921 step 3: getCnbRate(asOf) resolves the fixing that was in effect on a given day ───

    @Test
    fun `getCnbRate without asOf keeps asking for the latest still-valid fixing`() = runBlocking<Unit> {
        val repo = mockk<FxRateRepository>()
        val stored = cnbRate()
        coEvery { repo.findLatestBySource("EUR", "CZK", RateSource.CNB) } returns stored

        val result = service(repo, mockk()).getCnbRate("eur", "czk")

        assertThat(result).isEqualTo(stored)
        // The live daily path must not move onto the as-of query by accident: that query has no
        // wall-clock component, so silently routing today's revaluation through it would change
        // which row a same-day re-ingestion resolves to.
        coVerify(exactly = 0) { repo.findBySourceAsOf(any(), any(), any(), any()) }
    }

    @Test
    fun `getCnbRate with asOf asks for the window containing the START of that Prague day`() = runBlocking<Unit> {
        val repo = mockk<FxRateRepository>()
        val stored = cnbRate()
        val at = slot<Instant>()
        coEvery { repo.findBySourceAsOf(eq("EUR"), eq("CZK"), eq(RateSource.CNB), capture(at)) } returns stored

        val result = service(repo, mockk()).getCnbRate("eur", "czk", LocalDate.of(2026, 5, 27))

        assertThat(result).isEqualTo(stored)
        // Prague midnight, not UTC midnight and not "now": the validity bounds this is compared
        // against are written by `ingest` in exactly this zone, so any other instant would compare
        // a date against bounds it does not share an origin with.
        assertThat(at.captured).isEqualTo(LocalDate.of(2026, 5, 27).atStartOfDay(prague).toInstant())
        coVerify(exactly = 0) { repo.findLatestBySource(any(), any(), any()) }
    }

    @Test
    fun `getCnbRate with asOf returns null for a day no fixing covered - never a fallback`() = runBlocking<Unit> {
        val repo = mockk<FxRateRepository>()
        coEvery { repo.findBySourceAsOf(any(), any(), any(), any()) } returns null

        val result = service(repo, mockk()).getCnbRate("EUR", "CZK", LocalDate.of(2020, 1, 1))

        // The fallback IS the bug: it is how a backfill marks an old day at today's rate and
        // reports success. Absent must stay absent so ledger skips the leg loudly.
        assertThat(result).isNull()
        coVerify(exactly = 0) { repo.findLatestBySource(any(), any(), any()) }
    }

    private fun cnbRate() = FxRate(
        id = java.util.UUID.randomUUID(),
        baseCurrency = "EUR",
        quoteCurrency = "CZK",
        bidRate = java.math.BigDecimal("25.145"),
        askRate = java.math.BigDecimal("25.145"),
        rateType = RateType.INDICATIVE,
        source = RateSource.CNB,
        validFrom = expectedValidFrom,
        validTo = expectedValidFrom.plusSeconds(259_200),
        createdAt = Instant.parse("2026-05-30T12:30:00Z"),
    )
}
