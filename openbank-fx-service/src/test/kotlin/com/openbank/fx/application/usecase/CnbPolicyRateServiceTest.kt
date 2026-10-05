// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.fx.application.port.out.CnbPolicyRateDocument
import com.openbank.fx.application.port.out.CnbPolicyRateFeed
import com.openbank.fx.application.port.out.CnbPolicyRateProvenance
import com.openbank.fx.application.port.out.CnbPolicyRateRepository
import com.openbank.fx.application.port.out.CnbPolicyRateUpsertOutcome
import com.openbank.fx.domain.cnb.CnbPolicyInstrument
import com.openbank.fx.domain.cnb.CnbPolicyRateFact
import com.openbank.fx.domain.cnb.CnbPolicyRateObservation
import com.openbank.fx.domain.cnb.CnbPolicyRateUpsert
import com.openbank.fx.domain.event.CnbPolicyRatePublished
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.HexFormat

class CnbPolicyRateServiceTest {

    private val now = Instant.parse("2026-10-04T12:45:00Z")
    private val feed: CnbPolicyRateFeed = mockk()
    private val repo: CnbPolicyRateRepository = mockk()
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val service = CnbPolicyRateService(feed, repo, Clock.fixed(now, ZoneOffset.UTC), mapper)

    private val url = "https://www.cnb.cz/cs/casto-kladene-dotazy/.galleries/vyvoj_repo_historie.txt"
    private val body = requireNotNull(javaClass.getResourceAsStream("/cnb/policy-rates/vyvoj_repo_historie.txt"))
        .readBytes()

    @Test
    fun `ingest hashes the raw bytes, parses the whole file and upserts it with provenance`(): Unit = runBlocking {
        coEvery { feed.fetch(CnbPolicyInstrument.REPO_2W) } returns CnbPolicyRateDocument(url, body)
        val observations = slot<List<CnbPolicyRateObservation>>()
        val provenance = slot<CnbPolicyRateProvenance>()
        coEvery {
            repo.upsertAndPublish(CnbPolicyInstrument.REPO_2W, capture(observations), capture(provenance), any())
        } returns
            CnbPolicyRateUpsertOutcome(CnbPolicyRateUpsert(117, 0, 0), emptyList(), 117)

        val outcome = service.ingest(CnbPolicyInstrument.REPO_2W)

        assertThat(outcome.counts.inserted).isEqualTo(117)
        assertThat(observations.captured).hasSize(117)
        assertThat(provenance.captured.sourceUrl).isEqualTo(url)
        assertThat(provenance.captured.fetchedAt).isEqualTo(now)
        // The hash is of the bytes as downloaded — BOM included — not of the decoded text.
        assertThat(provenance.captured.contentSha256)
            .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)))
    }

    @Test
    fun `a malformed file never reaches the repository`(): Unit = runBlocking {
        val broken = (String(body, Charsets.UTF_8) + "\n2026XXXX|3,75").toByteArray()
        coEvery { feed.fetch(CnbPolicyInstrument.REPO_2W) } returns CnbPolicyRateDocument(url, broken)

        assertThatThrownBy { runBlocking { service.ingest(CnbPolicyInstrument.REPO_2W) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("1 malformed row(s)")
        coVerify(exactly = 0) { repo.upsertAndPublish(any(), any(), any(), any()) }
    }

    @Test
    fun `bytes that are not UTF-8 fail loudly instead of storing mojibake`(): Unit = runBlocking {
        val latin = byteArrayOf(0xC3.toByte(), 0x28)
        coEvery { feed.fetch(CnbPolicyInstrument.LOMBARD) } returns CnbPolicyRateDocument(url, latin)

        assertThatThrownBy { runBlocking { service.ingest(CnbPolicyInstrument.LOMBARD) } }
            .hasMessageContaining("not valid UTF-8")
    }

    @Test
    fun `a fact-only instrument cannot be ingested from a feed`() {
        assertThatThrownBy { runBlocking { service.ingest(CnbPolicyInstrument.MIN_RESERVE_RATIO) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a malformed workbook stores nothing for either instrument`(): Unit = runBlocking {
        coEvery { feed.fetchMinimumReserves() } returns CnbPolicyRateDocument("u", "<html>".toByteArray())

        assertThatThrownBy { runBlocking { service.ingestMinimumReserves() } }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { repo.upsertAndPublish(any(), any(), any(), any()) }
    }

    @Test
    fun `the workbook is ingested and its events carry the fact, its provenance and a deterministic key`(): Unit =
        runBlocking {
            val xlsxUrl = "https://www.cnb.cz/x/PMR_historie_zmen.xlsx"
            val xlsx = requireNotNull(
                javaClass.getResourceAsStream("/cnb/policy-rates/PMR_historie_zmen.xlsx"),
            ).readBytes()
            coEvery { feed.fetchMinimumReserves() } returns CnbPolicyRateDocument(xlsxUrl, xlsx)
            val factory = slot<(CnbPolicyRateFact) -> OutboxMessage>()
            val ratio = slot<List<CnbPolicyRateObservation>>()
            coEvery {
                repo.upsertAndPublish(CnbPolicyInstrument.MIN_RESERVE_RATIO, capture(ratio), any(), capture(factory))
            } returns CnbPolicyRateUpsertOutcome(CnbPolicyRateUpsert(2, 0, 0), emptyList(), 2)
            coEvery { repo.upsertAndPublish(CnbPolicyInstrument.MIN_RESERVE_REMUNERATION, any(), any(), any()) } returns
                CnbPolicyRateUpsertOutcome(CnbPolicyRateUpsert(1, 0, 0), emptyList(), 1)

            val outcomes = service.ingestMinimumReserves()

            assertThat(outcomes.keys)
                .containsExactlyInAnyOrder(
                    CnbPolicyInstrument.MIN_RESERVE_RATIO,
                    CnbPolicyInstrument.MIN_RESERVE_REMUNERATION,
                )
            assertThat(ratio.captured.map { it.effectiveFrom })
                .containsExactly(LocalDate.of(1999, 10, 7), LocalDate.of(2025, 1, 2))

            val fact = CnbPolicyRateFact(
                CnbPolicyInstrument.MIN_RESERVE_RATIO,
                LocalDate.of(2025, 1, 2),
                BigDecimal("0.04"),
                "https://www.cnb.cz/x.xlsx",
                Instant.parse("2026-10-04T00:00:00Z"),
                "a".repeat(64),
                "Vyhláška č. 323/2024 Sb.",
                previousRate = BigDecimal("0.02"),
                revisedAt = now,
            )
            val message = factory.captured(fact)
            assertThat(message.eventType).isEqualTo(CnbPolicyRatePublished.EVENT_TYPE)
            assertThat(message.aggregateId)
                .isEqualTo(
                    CnbPolicyRateService.aggregateId(CnbPolicyInstrument.MIN_RESERVE_RATIO, LocalDate.of(2025, 1, 2)),
                )
            val json = mapper.readTree(message.payload)
            assertThat(json["instrument"].asText()).isEqualTo("MIN_RESERVE_RATIO")
            assertThat(json["effectiveFrom"].asText()).isEqualTo("2025-01-02")
            assertThat(json["rate"].decimalValue()).isEqualByComparingTo("0.04")
            assertThat(json["revised"].asBoolean()).isTrue()
            assertThat(json["previousRate"].decimalValue()).isEqualByComparingTo("0.02")
            assertThat(json["sourceService"].asText()).isEqualTo("fx-service")
            assertThat(json["contentSha256"].asText()).hasSize(64)
        }
}
