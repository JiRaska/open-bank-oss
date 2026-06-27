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
}
