// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.out.ListImportOutcome
import com.openbank.sanctions.application.port.out.SanctionsEntryRepository
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.SanctionsEntry
import com.openbank.sanctions.domain.model.SanctionsListType
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
 * Covers the import pipeline: format dispatch (OFAC XML / EU FSF XML / OpenSanctions CSV /
 * skipped formats), the outcome enum every attempt resolves to (issue #8362 — never an
 * ambiguous count), name normalization, CSV parsing edge cases, and the catch-all error path.
 * Uses a real loopback [HttpServer] instead of mocking [java.net.http.HttpClient] directly —
 * the service builds its own HttpClient internally, so the only seam available is the actual
 * socket.
 */
class SanctionsImportServiceTest {

    private val entryRepo = mockk<SanctionsEntryRepository>()
    private val clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val service = SanctionsImportService(entryRepo, clock)

    /** EU list pinned to the OpenSanctions CSV source so CSV-format tests keep their loopback URL. */
    private val openSanctionsEuService = SanctionsImportService(
        entryRepo,
        clock,
        euSource = SanctionsImportService.EU_SOURCE_OPENSANCTIONS,
    )

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
        val result = service.importList(SanctionsListType.FATF_HIGH_RISK, "https://example.com/unused")

        assertThat(result.outcome).isEqualTo(ListImportOutcome.SKIPPED_NOT_ENTITY_BASED)
        assertThat(result.entriesImported).isZero()
    }

    @Test
    fun `importList skips CNB_DOMESTIC as seeded via migration`(): Unit = runBlocking {
        val result = service.importList(SanctionsListType.CNB_DOMESTIC, "https://example.com/unused")

        assertThat(result.outcome).isEqualTo(ListImportOutcome.SKIPPED_NOT_ENTITY_BASED)
    }

    @Test
    fun `importList reports FAILED_KEPT_EXISTING when the download fails`(): Unit = runBlocking {
        val result = service.importList(SanctionsListType.OFAC_SDN, "http://127.0.0.1:1/does-not-exist")

        assertThat(result.outcome).isEqualTo(ListImportOutcome.FAILED_KEPT_EXISTING)
        assertThat(result.entriesImported).isZero()
        assertThat(result.detail).isNotBlank()
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

        val result = service.importList(SanctionsListType.OFAC_SDN, url)

        assertThat(result.outcome).isEqualTo(ListImportOutcome.IMPORTED)
        assertThat(result.entriesImported).isEqualTo(2)
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

        val result = service.importList(SanctionsListType.OFAC_SDN, url)

        assertThat(result.outcome).isEqualTo(ListImportOutcome.EMPTY_FEED)
    }

    @Test
    fun `importList parses OpenSanctions CSV and upserts entries with defaults`(): Unit = runBlocking {
        val csv = "id,schema,name,aliases,birth_date,countries,addresses,identifiers,sanctions," +
            "phones,emails,program_ids,dataset,first_seen,last_seen,last_change\n" +
            "os-1,Person,Andrej Babis,\"Ondrej Babis;A. Babis\",1954-09-13,cz,,,,,,,,,,\n" +
            "os-2,Organization,Acme Sanctioned Co,,,,,,,,,\"EU-SANCTIONS\",,,,\n"
        val url = serveOnce(csv, "text/csv")

        val entriesSlot = slot<List<SanctionsEntry>>()
        coEvery { entryRepo.upsertAll(capture(entriesSlot)) } returns 2
        coEvery { entryRepo.deactivateMissing(SanctionsListType.PEP_GLOBAL, setOf("os-1", "os-2")) } returns 0

        val result = service.importList(SanctionsListType.PEP_GLOBAL, url)

        assertThat(result.outcome).isEqualTo(ListImportOutcome.IMPORTED)
        assertThat(result.entriesImported).isEqualTo(2)
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
    fun `importList reports EMPTY_FEED when CSV header is missing required columns`(): Unit = runBlocking {
        val csv = "foo,bar\nbaz,qux\n"
        val url = serveOnce(csv, "text/csv")

        val result = openSanctionsEuService.importList(SanctionsListType.EU_CONSOLIDATED, url)

        assertThat(result.outcome).isEqualTo(ListImportOutcome.EMPTY_FEED)
    }

    @Test
    fun `importList skips blank CSV rows and rows with blank name`(): Unit = runBlocking {
        val csv = "id,schema,name,aliases,birth_date,countries,addresses,identifiers,sanctions," +
            "phones,emails,program_ids,dataset,first_seen,last_seen,last_change\n" +
            "\n" +
            "os-3,Person,,,,,,,,,,,,,,\n"
        val url = serveOnce(csv, "text/csv")

        // Both rows are skipped (blank line, blank name) — the reconciliation sweep at the end
        // still runs (the stream completed without error), just with an empty present set.
        coEvery { entryRepo.deactivateMissing(SanctionsListType.UN_CONSOLIDATED, emptySet()) } returns 0

        val result = service.importList(SanctionsListType.UN_CONSOLIDATED, url)

        assertThat(result.outcome).isEqualTo(ListImportOutcome.EMPTY_FEED)
    }

    @Test
    fun `importList handles vessel and aircraft schema types`(): Unit = runBlocking {
        val csv = "id,schema,name,aliases,birth_date,countries,addresses,identifiers,sanctions," +
            "phones,emails,program_ids,dataset,first_seen,last_seen,last_change\n" +
            "os-4,Vessel,MV Example,,,,,,,,,,,,,\n" +
            "os-5,Aircraft,N12345,,,,,,,,,,,,,\n"
        val url = serveOnce(csv, "text/csv")

        val entriesSlot = slot<List<SanctionsEntry>>()
        coEvery { entryRepo.upsertAll(capture(entriesSlot)) } returns 2
        coEvery { entryRepo.deactivateMissing(SanctionsListType.HM_TREASURY, setOf("os-4", "os-5")) } returns 0

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

        val entriesSlot = slot<List<SanctionsEntry>>()
        coEvery { entryRepo.upsertAll(capture(entriesSlot)) } returns 1
        coEvery { entryRepo.deactivateMissing(SanctionsListType.PEP_GLOBAL, setOf("os-6")) } returns 0

        service.importList(SanctionsListType.PEP_GLOBAL, url)

        assertThat(entriesSlot.captured.single().primaryName).isEqualTo("""Doe, John "The Rock"""")
    }

    // ──── deactivateMissing — the mark-and-sweep reconciliation contract ─────

    @Test
    fun `a mid-stream failure never calls deactivateMissing, leaving existing entries untouched`(): Unit = runBlocking {
        val csv = "id,schema,name,aliases,birth_date,countries,addresses,identifiers,sanctions," +
            "phones,emails,program_ids,dataset,first_seen,last_seen,last_change\n" +
            "os-1,Person,First Entry,,,,,,,,,,,,,\n" +
            "os-2,Person,Second Entry,,,,,,,,,,,,,\n"
        val url = serveOnce(csv, "text/csv")

        // The batch flush throws partway through the stream (simulates a DB hiccup between
        // reading rows) — this must propagate out of importOpenSanctionsCsv, resolve to
        // FAILED_KEPT_EXISTING in runImport's catch-all, and never reach deactivateMissing.
        coEvery { entryRepo.upsertAll(any()) } throws RuntimeException("connection reset")

        val result = service.importList(SanctionsListType.PEP_GLOBAL, url)

        assertThat(result.outcome).isEqualTo(ListImportOutcome.FAILED_KEPT_EXISTING)
        coVerify(exactly = 0) { entryRepo.deactivateMissing(any(), any()) }
    }

    @Test
    fun `deactivateMissing runs once, after every batch has been upserted, not before`(): Unit = runBlocking {
        // Force two batches by dropping the batch size threshold via a large-enough row count
        // is impractical here (IMPORT_BATCH_SIZE = 500); instead assert call ORDER directly:
        // upsertAll must be recorded before deactivateMissing for the same run.
        val csv = "id,schema,name,aliases,birth_date,countries,addresses,identifiers,sanctions," +
            "phones,emails,program_ids,dataset,first_seen,last_seen,last_change\n" +
            "os-1,Person,First Entry,,,,,,,,,,,,,\n"
        val url = serveOnce(csv, "text/csv")

        coEvery { entryRepo.upsertAll(any()) } returns 1
        coEvery { entryRepo.deactivateMissing(SanctionsListType.PEP_GLOBAL, setOf("os-1")) } returns 0

        service.importList(SanctionsListType.PEP_GLOBAL, url)

        coVerifyOrder {
            entryRepo.upsertAll(any())
            entryRepo.deactivateMissing(SanctionsListType.PEP_GLOBAL, setOf("os-1"))
        }
    }

    // ──── EU consolidated list — first-party FSF XML (issue #8362) ────────────

    @Test
    fun `the default EU source imports the official FSF XML and upserts entries`(): Unit = runBlocking {
        val fsfXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <export xmlns="http://eu.europa.ec/fpi/fsd/export" generationDate="2026-08-05T16:47:04.449+02:00" globalFileId="184961">
                <sanctionEntity designationDetails="" unitedNationId="" euReferenceNumber="EU.27.28" logicalId="13">
                    <regulation regulationType="regulation" programme="IRQ" logicalId="348"/>
                    <subjectType code="person" classificationCode="P"/>
                    <nameAlias firstName="Saddam" middleName="" lastName="Hussein Al-Tikriti" wholeName="Saddam Hussein Al-Tikriti" strong="true" logicalId="17"/>
                    <nameAlias wholeName="Abu Ali" strong="true" logicalId="19"/>
                    <citizenship region="" countryIso2Code="IQ" countryDescription="IRAQ" logicalId="1"/>
                    <birthdate circa="false" birthdate="1937-04-28" logicalId="1"/>
                </sanctionEntity>
                <sanctionEntity euReferenceNumber="EU.12345.67" logicalId="999">
                    <subjectType code="enterprise" classificationCode="E"/>
                    <nameAlias wholeName="ACME Trading" strong="true" logicalId="1"/>
                    <regulation programme="TAQA" logicalId="2"/>
                </sanctionEntity>
            </export>
        """.trimIndent()
        val url = serveOnce(fsfXml, "application/xml")
        val fsfService = SanctionsImportService(entryRepo, clock, euFsfUrl = url)

        val entriesSlot = slot<List<SanctionsEntry>>()
        coEvery { entryRepo.upsertAll(capture(entriesSlot)) } returns 2
        coEvery {
            entryRepo.deactivateMissing(SanctionsListType.EU_CONSOLIDATED, setOf("eu-fsf-13", "eu-fsf-999"))
        } returns 0

        // The default euSource is eu-fsf — no explicit source selection in this service instance.
        val result = fsfService.importList(SanctionsListType.EU_CONSOLIDATED, "https://ignored.example/seed-url")

        assertThat(result.outcome).isEqualTo(ListImportOutcome.IMPORTED)
        assertThat(result.entriesImported).isEqualTo(2)
        val entries = entriesSlot.captured

        val person = entries.first { it.externalId == "eu-fsf-13" }
        assertThat(person.primaryName).isEqualTo("Saddam Hussein Al-Tikriti")
        assertThat(person.entityType).isEqualTo(EntityType.INDIVIDUAL)
        assertThat(person.aliases).containsExactly("Abu Ali")
        assertThat(person.dateOfBirth).isEqualTo("1937-04-28")
        assertThat(person.nationalities).containsExactly("IQ")
        assertThat(person.programs).containsExactly("IRQ")
        assertThat(person.listType).isEqualTo(SanctionsListType.EU_CONSOLIDATED)
        // searchText carries the normalized primary name and aliases for the pg_trgm match.
        assertThat(person.searchText).contains("saddam hussein al-tikriti").contains("abu ali")

        val org = entries.first { it.externalId == "eu-fsf-999" }
        assertThat(org.entityType).isEqualTo(EntityType.ORGANIZATION)
        assertThat(org.programs).containsExactly("TAQA")
    }

    @Test
    fun `an FSF entity without a programme falls back to the EU default`(): Unit = runBlocking {
        val fsfXml = """
            <export xmlns="http://eu.europa.ec/fpi/fsd/export">
                <sanctionEntity logicalId="42">
                    <subjectType code="person"/>
                    <nameAlias wholeName="No Programme" strong="true" logicalId="1"/>
                </sanctionEntity>
            </export>
        """.trimIndent()
        val url = serveOnce(fsfXml, "application/xml")
        val fsfService = SanctionsImportService(entryRepo, clock, euFsfUrl = url)

        val entriesSlot = slot<List<SanctionsEntry>>()
        coEvery { entryRepo.upsertAll(capture(entriesSlot)) } returns 1
        coEvery { entryRepo.deactivateMissing(SanctionsListType.EU_CONSOLIDATED, setOf("eu-fsf-42")) } returns 0

        val result = fsfService.importList(SanctionsListType.EU_CONSOLIDATED, "https://ignored.example/seed-url")

        assertThat(result.outcome).isEqualTo(ListImportOutcome.IMPORTED)
        assertThat(entriesSlot.captured.single().programs).containsExactly("EU-SANCTIONS")
    }

    @Test
    fun `the seed source reports SEED_FALLBACK_NON_PRODUCTION and never touches the store`(): Unit = runBlocking {
        val seedService = SanctionsImportService(
            entryRepo,
            clock,
            euSource = SanctionsImportService.EU_SOURCE_SEED,
        )

        val result = seedService.importList(SanctionsListType.EU_CONSOLIDATED, "https://example.com/unused")

        assertThat(result.outcome).isEqualTo(ListImportOutcome.SEED_FALLBACK_NON_PRODUCTION)
        assertThat(result.detail).contains("NON-PRODUCTION")
        coVerify(exactly = 0) { entryRepo.upsertAll(any()) }
        coVerify(exactly = 0) { entryRepo.deactivateMissing(any(), any()) }
    }

    @Test
    fun `an unreachable FSF endpoint reports FAILED_KEPT_EXISTING, not an empty import`(): Unit = runBlocking {
        val fsfService = SanctionsImportService(
            entryRepo,
            clock,
            euFsfUrl = "http://127.0.0.1:1/does-not-exist",
        )

        val result = fsfService.importList(SanctionsListType.EU_CONSOLIDATED, "https://example.com/unused")

        assertThat(result.outcome).isEqualTo(ListImportOutcome.FAILED_KEPT_EXISTING)
        coVerify(exactly = 0) { entryRepo.deactivateMissing(any(), any()) }
    }
}
