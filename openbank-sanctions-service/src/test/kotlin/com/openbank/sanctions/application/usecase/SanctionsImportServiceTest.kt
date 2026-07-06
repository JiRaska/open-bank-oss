// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.out.SanctionsEntryRepository
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.SanctionsEntry
import com.openbank.sanctions.domain.model.SanctionsListType
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Covers the import pipeline: format dispatch (OFAC XML / OpenSanctions CSV / skipped formats),
 * name normalization, CSV parsing edge cases, and the catch-all error path. Uses a real loopback
 * [HttpServer] instead of mocking [java.net.http.HttpClient] directly — the service builds its own
 * HttpClient internally, so the only seam available is the actual socket.
 */
class SanctionsImportServiceTest {

    private val entryRepo = mockk<SanctionsEntryRepository>()
    private val clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val service = SanctionsImportService(entryRepo, clock)

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    private fun serveOnce(body: String, contentType: String = "text/plain"): String {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/feed") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        server = httpServer
        return "http://127.0.0.1:${httpServer.address.port}/feed"
    }

    // ──── normalizeForSearch ─────────────────────────────────────────────────

    @Test
    fun `normalizeForSearch strips diacritics, lowercases and trims`() {
        assertThat(service.normalizeForSearch("  Andrej Babiš  ")).isEqualTo("andrej babis")
        assertThat(service.normalizeForSearch("VLADIMIR PUTIN")).isEqualTo("vladimir putin")
        assertThat(service.normalizeForSearch("Śrí Lãnká")).isEqualTo("sri lanka")
    }

    // ──── importList — format dispatch ───────────────────────────────────────

    @Test
    fun `importList skips FATF_HIGH_RISK as country-risk`(): Unit = runBlocking {
        val count = service.importList(SanctionsListType.FATF_HIGH_RISK, "https://example.com/unused")

        assertThat(count).isZero()
    }

    @Test
    fun `importList skips CNB_DOMESTIC as seeded via migration`(): Unit = runBlocking {
        val count = service.importList(SanctionsListType.CNB_DOMESTIC, "https://example.com/unused")

        assertThat(count).isZero()
    }

    @Test
    fun `importList returns zero and swallows exception when download fails`(): Unit = runBlocking {
        val count = service.importList(SanctionsListType.OFAC_SDN, "http://127.0.0.1:1/does-not-exist")

        assertThat(count).isZero()
    }

    @Test
    fun `importList parses OFAC SDN XML and upserts entries`(): Unit = runBlocking {
        val xml = """
            <?xml version="1.0"?>
            <sdnList>
              <sdnEntry>
                <uid>17766</uid>
                <firstName>Vladimir</firstName>
                <lastName>Putin</lastName>
                <sdnType>Individual</sdnType>
                <programList><program>RUSSIA-EO14024</program></programList>
                <akaList>
                  <aka>
                    <firstName>Vova</firstName>
                    <lastName>Putin</lastName>
                  </aka>
                </akaList>
                <dateOfBirthList>
                  <dateOfBirthItem><dateOfBirth>07 Oct 1952</dateOfBirth></dateOfBirthItem>
                </dateOfBirthList>
              </sdnEntry>
              <sdnEntry>
                <uid>99999</uid>
                <firstName>Acme</firstName>
                <lastName>Corp</lastName>
                <sdnType>Entity</sdnType>
              </sdnEntry>
            </sdnList>
        """.trimIndent()
        val url = serveOnce(xml, "application/xml")

        val entriesSlot = slot<List<SanctionsEntry>>()
        coEvery { entryRepo.upsertAll(capture(entriesSlot)) } returns 2

        val count = service.importList(SanctionsListType.OFAC_SDN, url)

        assertThat(count).isEqualTo(2)
        val entries = entriesSlot.captured
        assertThat(entries).hasSize(2)

        val putin = entries.first { it.externalId == "ofac-17766" }
        assertThat(putin.primaryName).isEqualTo("Vladimir Putin")
        assertThat(putin.entityType).isEqualTo(EntityType.INDIVIDUAL)
        assertThat(putin.aliases).containsExactly("Vova Putin")
        assertThat(putin.dateOfBirth).isEqualTo("07 Oct 1952")
        assertThat(putin.programs).containsExactly("RUSSIA-EO14024")
        assertThat(putin.listType).isEqualTo(SanctionsListType.OFAC_SDN)

        val acme = entries.first { it.externalId == "ofac-99999" }
        assertThat(acme.entityType).isEqualTo(EntityType.ORGANIZATION)
        assertThat(acme.primaryName).isEqualTo("Acme Corp")
    }

    @Test
    fun `importList skips OFAC entries without uid or with blank name`(): Unit = runBlocking {
        val xml = """
            <sdnList>
              <sdnEntry>
                <firstName>NoUid</firstName>
                <lastName>Person</lastName>
              </sdnEntry>
              <sdnEntry>
                <uid>123</uid>
                <firstName></firstName>
                <lastName></lastName>
              </sdnEntry>
            </sdnList>
        """.trimIndent()
        val url = serveOnce(xml, "application/xml")

        coEvery { entryRepo.upsertAll(any()) } returns 0

        val count = service.importList(SanctionsListType.OFAC_SDN, url)

        assertThat(count).isZero()
    }

    @Test
    fun `importList parses OpenSanctions CSV and upserts entries with defaults`(): Unit = runBlocking {
        val csv = "id,schema,name,aliases,birth_date,countries,addresses,identifiers,sanctions," +
            "phones,emails,program_ids,dataset,first_seen,last_seen,last_change\n" +
            "os-1,Person,Andrej Babis,\"Ondrej Babis;A. Babis\",1954-09-13,cz,,,,,,,,,,\n" +
            "os-2,Organization,Acme Sanctioned Co,,,,,,,,,\"EU-SANCTIONS\",,,,\n"
        val url = serveOnce(csv, "text/csv")

        coEvery { entryRepo.deactivateByListType(SanctionsListType.PEP_GLOBAL) } returns 0
        val entriesSlot = slot<List<SanctionsEntry>>()
        coEvery { entryRepo.upsertAll(capture(entriesSlot)) } returns 2

        val count = service.importList(SanctionsListType.PEP_GLOBAL, url)

        assertThat(count).isEqualTo(2)
        val entries = entriesSlot.captured
        val person = entries.first { it.externalId == "os-1" }
        assertThat(person.primaryName).isEqualTo("Andrej Babis")
        assertThat(person.aliases).containsExactlyInAnyOrder("Ondrej Babis", "A. Babis")
        assertThat(person.nationalities).containsExactly("cz")
        assertThat(person.dateOfBirth).isEqualTo("1954-09-13")
        assertThat(person.entityType).isEqualTo(EntityType.INDIVIDUAL)
        assertThat(person.programs).containsExactly("PEP") // default program applied, no program_ids

        val org = entries.first { it.externalId == "os-2" }
        assertThat(org.entityType).isEqualTo(EntityType.ORGANIZATION)
        assertThat(org.programs).containsExactly("EU-SANCTIONS") // program_ids column wins
    }

    @Test
    fun `importList returns zero when CSV header is missing required columns`(): Unit = runBlocking {
        val csv = "foo,bar\nbaz,qux\n"
        val url = serveOnce(csv, "text/csv")

        val count = service.importList(SanctionsListType.EU_CONSOLIDATED, url)

        assertThat(count).isZero()
    }

    @Test
    fun `importList skips blank CSV rows and rows with blank name`(): Unit = runBlocking {
        val csv = "id,schema,name,aliases,birth_date,countries,addresses,identifiers,sanctions," +
            "phones,emails,program_ids,dataset,first_seen,last_seen,last_change\n" +
            "\n" +
            "os-3,Person,,,,,,,,,,,,,,\n"
        val url = serveOnce(csv, "text/csv")

        coEvery { entryRepo.deactivateByListType(SanctionsListType.UN_CONSOLIDATED) } returns 0

        val count = service.importList(SanctionsListType.UN_CONSOLIDATED, url)

        assertThat(count).isZero()
    }

    @Test
    fun `importList handles vessel and aircraft schema types`(): Unit = runBlocking {
        val csv = "id,schema,name,aliases,birth_date,countries,addresses,identifiers,sanctions," +
            "phones,emails,program_ids,dataset,first_seen,last_seen,last_change\n" +
            "os-4,Vessel,MV Example,,,,,,,,,,,,,\n" +
            "os-5,Aircraft,N12345,,,,,,,,,,,,,\n"
        val url = serveOnce(csv, "text/csv")

        coEvery { entryRepo.deactivateByListType(SanctionsListType.HM_TREASURY) } returns 0
        val entriesSlot = slot<List<SanctionsEntry>>()
        coEvery { entryRepo.upsertAll(capture(entriesSlot)) } returns 2

        service.importList(SanctionsListType.HM_TREASURY, url)

        val entries = entriesSlot.captured
        assertThat(entries.first { it.externalId == "os-4" }.entityType).isEqualTo(EntityType.VESSEL)
        assertThat(entries.first { it.externalId == "os-5" }.entityType).isEqualTo(EntityType.AIRCRAFT)
    }

    @Test
    fun `importList handles quoted CSV fields with embedded commas and escaped quotes`(): Unit = runBlocking {
        val csv = "id,schema,name,aliases,birth_date,countries,addresses,identifiers,sanctions," +
            "phones,emails,program_ids,dataset,first_seen,last_seen,last_change\n" +
            "os-6,Person,\"Doe, John \"\"The Rock\"\"\",,,,,,,,,,,,,\n"
        val url = serveOnce(csv, "text/csv")

        coEvery { entryRepo.deactivateByListType(SanctionsListType.PEP_GLOBAL) } returns 0
        val entriesSlot = slot<List<SanctionsEntry>>()
        coEvery { entryRepo.upsertAll(capture(entriesSlot)) } returns 1

        service.importList(SanctionsListType.PEP_GLOBAL, url)

        assertThat(entriesSlot.captured.single().primaryName).isEqualTo("""Doe, John "The Rock"""")
    }
}
