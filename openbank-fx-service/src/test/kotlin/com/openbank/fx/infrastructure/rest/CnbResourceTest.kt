// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.rest

import com.openbank.fx.application.port.`in`.CnbIngestionResult
import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.IngestCnbFixingCommand
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CnbResourceTest {

    private lateinit var ingestion: CnbRateIngestionUseCase
    private lateinit var resource: CnbResource

    @BeforeEach
    fun setUp() {
        ingestion = mockk()
        resource = CnbResource(ingestion)
    }

    private fun rate(base: String = "EUR") = FxRate(
        id = UUID.randomUUID(),
        baseCurrency = base,
        quoteCurrency = "CZK",
        bidRate = BigDecimal("25.145"),
        askRate = BigDecimal("25.145"),
        rateType = RateType.INDICATIVE,
        source = RateSource.CNB,
        validFrom = Instant.parse("2026-05-30T00:00:00Z"),
        validTo = Instant.parse("2026-06-02T00:00:00Z"),
        createdAt = Instant.parse("2026-05-30T12:40:00Z"),
    )

    @Test
    fun `ingest with no date parameter ingests the latest fixing`(): Unit = runBlocking {
        val cmd = slot<IngestCnbFixingCommand>()
        val result = CnbIngestionResult(LocalDate.of(2026, 5, 30), 104, 3, 0, listOf("EUR", "USD", "GBP"))
        coEvery { ingestion.ingest(capture(cmd)) } returns result

        val resp: Response = resource.ingest(date = null)

        assertThat(resp.status).isEqualTo(200)
        assertThat(cmd.captured.date).isNull()
        assertThat(resp.entity).isEqualTo(result)
    }

    @Test
    fun `ingest with a blank date parameter is treated as latest`(): Unit = runBlocking {
        val cmd = slot<IngestCnbFixingCommand>()
        coEvery { ingestion.ingest(capture(cmd)) } returns
            CnbIngestionResult(LocalDate.of(2026, 5, 30), 104, 0, 3, emptyList())

        resource.ingest(date = "   ")

        assertThat(cmd.captured.date).isNull()
    }

    @Test
    fun `ingest with an explicit date backfills that business day`(): Unit = runBlocking {
        val cmd = slot<IngestCnbFixingCommand>()
        coEvery { ingestion.ingest(capture(cmd)) } returns
            CnbIngestionResult(LocalDate.of(2026, 5, 28), 103, 3, 0, listOf("EUR", "USD", "GBP"))

        val resp = resource.ingest(date = "2026-05-28")

        assertThat(resp.status).isEqualTo(200)
        assertThat(cmd.captured.date).isEqualTo(LocalDate.of(2026, 5, 28))
        coVerify(exactly = 1) { ingestion.ingest(IngestCnbFixingCommand(LocalDate.of(2026, 5, 28))) }
    }

    @Test
    fun `getCnbRate returns 200 with the latest ingested rate`(): Unit = runBlocking {
        val stored = rate()
        coEvery { ingestion.getCnbRate("EUR", "CZK", null) } returns stored

        val resp = resource.getCnbRate(base = "eur")

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity).isEqualTo(stored)
        coVerify(exactly = 1) { ingestion.getCnbRate("EUR", "CZK", null) }
    }

    @Test
    fun `getCnbRate returns 404 when no rate has been ingested for the currency`(): Unit = runBlocking {
        coEvery { ingestion.getCnbRate("CHF", "CZK", null) } returns null

        val resp = resource.getCnbRate(base = "chf")

        assertThat(resp.status).isEqualTo(404)
        // The 404 message echoes the raw path param, not the uppercased lookup key (CnbResource.getCnbRate).
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.entity as Map<String, String>)["error"]).contains("chf/CZK")
    }
}
