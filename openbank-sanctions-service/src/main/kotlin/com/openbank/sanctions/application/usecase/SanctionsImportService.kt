// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.libs.observability.DomainMetrics
import com.openbank.sanctions.application.port.out.ListImportOutcome
import com.openbank.sanctions.application.port.out.ListImportResult
import com.openbank.sanctions.application.port.out.SanctionsEntryRepository
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.SanctionsEntry
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.infrastructure.importer.EuFsfSaxParser
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.SAXParserFactory

/**
 * Downloads, parses and upserts sanctions/PEP list entries.
 *
 * Supported formats:
 *  - OFAC SDN     → sdn.xml (Treasury XML)               — SAX streaming, O(1) memory
 *  - EU_CONSOLIDATED → official EU FSF XML (first-party) by default; OpenSanctions CSV selectable
 *  - PEP_GLOBAL / UN / HM → OpenSanctions targets.simple.csv — BufferedReader streaming, batch upsert
 *  - FATF         → country-risk, not entity-based — skipped
 *  - CNB          → no machine-readable feed — seeded in Flyway V6
 *
 * Every import attempt returns a [ListImportResult] whose outcome is one of the
 * [ListImportOutcome] values — never an ambiguous count (issue #8362 / the #4348 rule: a
 * failed, skipped or seed-fallback import must not look like a working one). Each attempt also
 * increments `openbank.sanctions.list.imports{list_type,outcome}` so "the EU list has not
 * reported outcome=imported in N days" is alertable.
 *
 * Called by [SanctionsListService.refresh].
 * Memory design: never buffers the full HTTP body as String; parses from InputStream.
 */
@ApplicationScoped
class SanctionsImportService(
    private val entryRepo: SanctionsEntryRepository,
    private val clock: Clock,
    private val metrics: DomainMetrics? = null,
    private val euSource: String = EU_SOURCE_EU_FSF,
    private val euFsfUrl: String = DEFAULT_EU_FSF_URL,
) {

    // CDI entry point: injects the production UTC clock and the configured EU list source.
    // Tests use the primary constructor with a fixed Clock for deterministic timestamps
    // (ADR-0100 Layer 1) and explicit source selection.
    @Inject
    constructor(
        entryRepo: SanctionsEntryRepository,
        metrics: DomainMetrics,
        @ConfigProperty(name = "openbank.sanctions.eu.source", defaultValue = EU_SOURCE_EU_FSF) euSource: String,
        @ConfigProperty(name = "openbank.sanctions.eu-fsf.url", defaultValue = DEFAULT_EU_FSF_URL) euFsfUrl: String,
    ) : this(entryRepo, Clock.systemUTC(), metrics, euSource, euFsfUrl)

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    /**
     * Downloads and imports [listType] from [sourceUrl].
     *
     * @return the import outcome — [ListImportOutcome.IMPORTED] with the upserted count, or a
     * named non-success (see [ListImportResult]); the caller must key its bookkeeping on the
     * outcome, never on "count > 0".
     */
    suspend fun importList(listType: SanctionsListType, sourceUrl: String): ListImportResult {
        Log.infof("Importing %s from %s", listType, sourceUrl)
        val result = runImport(listType, sourceUrl)
        metrics?.sanctionsListImport(listType.name, result.outcome.name.lowercase())
        if (result.outcome != ListImportOutcome.IMPORTED) {
            Log.warnf(
                "List %s import outcome %s (%s) — stored entries left untouched",
                listType,
                result.outcome,
                result.detail ?: "no detail",
            )
        }
        return result
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runImport(listType: SanctionsListType, sourceUrl: String): ListImportResult = try {
        when (listType) {
            // OFAC SDN: official Treasury XML — SAX streaming
            SanctionsListType.OFAC_SDN -> importedOrEmpty(importOfacSdn(sourceUrl))
            // EU consolidated list: first-party EU FSF XML by default (issue #8362);
            // OpenSanctions CSV stays selectable for rollback; `seed` = the non-production
            // Flyway sample entries (local dev only).
            SanctionsListType.EU_CONSOLIDATED -> when (euSource) {
                EU_SOURCE_OPENSANCTIONS -> importedOrEmpty(importOpenSanctionsCsv(sourceUrl, listType, EU_PROGRAMS))
                EU_SOURCE_SEED -> ListImportResult.seedFallback(
                    "openbank.sanctions.eu.source=seed — EU list runs on the Flyway V6 sample " +
                        "entries; NON-PRODUCTION configuration",
                )
                else -> importedOrEmpty(importEuFsf())
            }
            // OpenSanctions-normalised CSV for PEP, UN, HM — stream line-by-line
            SanctionsListType.PEP_GLOBAL -> importedOrEmpty(
                importOpenSanctionsCsv(sourceUrl, listType, listOf("PEP")),
            )
            SanctionsListType.UN_CONSOLIDATED -> importedOrEmpty(
                importOpenSanctionsCsv(sourceUrl, listType, listOf("UN-SANCTIONS")),
            )
            SanctionsListType.HM_TREASURY -> importedOrEmpty(
                importOpenSanctionsCsv(sourceUrl, listType, listOf("HMT-SANCTIONS")),
            )
            SanctionsListType.FATF_HIGH_RISK ->
                ListImportResult.skippedNotEntityBased("FATF is country-risk — no entity feed")
            SanctionsListType.CNB_DOMESTIC ->
                ListImportResult.skippedNotEntityBased(
                    "CNB has no machine-readable feed — entries seeded via migration",
                )
        }
    } catch (ex: Exception) {
        // Deliberately broad: any fetch/parse/store failure means the SAME thing to the
        // caller — the previously stored entries were kept — and the outcome names it. The
        // mid-stream partial-wipe case is handled inside the importers (deactivateMissing
        // only runs after a fully-consumed stream, #1432), never by this catch.
        ListImportResult.failedKeptExisting("${ex.javaClass.simpleName}: ${ex.message}")
    }

    private fun importedOrEmpty(count: Int): ListImportResult =
        if (count > 0) ListImportResult.imported(count) else ListImportResult.emptyFeed()

    // ──────────────────────────────────────────────────────────────────────────
    // EU FSF (official consolidated list, first-party) — SAX streaming (issue #8362)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Import the EU consolidated list from the official FSF XML endpoint ([euFsfUrl]) rather
     * than the OpenSanctions-normalised mirror — the migration V7 switch to OpenSanctions was a
     * workaround for an endpoint that then returned HTML redirects; the first-party feed is the
     * source of record and is what a compliance audit expects the bank to screen against.
     *
     * Same durability contract as the CSV path: entries upsert in batches, and the
     * deactivateMissing reconciliation sweep runs ONLY after the whole stream parsed — a
     * mid-stream failure propagates and the stored list is left untouched (#1432).
     */
    private suspend fun importEuFsf(): Int {
        val inputStream = withContext(Dispatchers.IO) { httpGetStream(euFsfUrl) }
        val entities = withContext(Dispatchers.IO) {
            inputStream.use { EuFsfSaxParser.parse(it) }
        }
        Log.infof("SAX-parsed %d EU FSF sanction entities", entities.size)

        var total = 0
        val seenExternalIds = mutableSetOf<String>()
        for (chunk in entities.chunked(IMPORT_BATCH_SIZE)) {
            val entries = chunk.map { fsf ->
                val externalId = "eu-fsf-${fsf.logicalId}"
                seenExternalIds += externalId
                buildEntry(
                    listType = SanctionsListType.EU_CONSOLIDATED,
                    externalId = externalId,
                    entityType = if (fsf.subjectType == "person") EntityType.INDIVIDUAL else EntityType.ORGANIZATION,
                    primaryName = fsf.primaryName,
                    aliases = fsf.aliases,
                    dateOfBirth = fsf.dateOfBirth,
                    nationalities = fsf.nationalities,
                    programs = fsf.programmes.ifEmpty { EU_PROGRAMS },
                )
            }
            total += entryRepo.upsertAll(entries)
        }

        val deactivated = entryRepo.deactivateMissing(SanctionsListType.EU_CONSOLIDATED, seenExternalIds)
        Log.infof("Imported %d EU FSF entries (%d no longer present, deactivated)", total, deactivated)
        return total
    }

    // ──────────────────────────────────────────────────────────────────────────
    // OFAC SDN  (sdn.xml) — SAX streaming, O(1) peak memory per entry
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun importOfacSdn(url: String): Int = withContext(Dispatchers.IO) {
        val inputStream = httpGetStream(url)
        val allEntries = mutableListOf<SanctionsEntry>()

        val saxFactory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        saxFactory.newSAXParser().parse(
            inputStream,
            object : DefaultHandler() {
                private var inEntry = false
                private var inAka = false
                private var inDobItem = false
                private val text = StringBuilder()

                private var uid: String? = null
                private var firstName: String? = null
                private var lastName: String? = null
                private var sdnType: String? = null
                private val programs = mutableListOf<String>()
                private val aliases = mutableListOf<String>()
                private var dob: String? = null

                private var akaFirst: String? = null
                private var akaLast: String? = null

                override fun startElement(uri: String, local: String, qName: String, attrs: Attributes) {
                    text.clear()
                    when (qName) {
                        "sdnEntry" -> {
                            inEntry = true
                            uid = null
                            firstName = null
                            lastName = null
                            sdnType = null
                            dob = null
                            programs.clear()
                            aliases.clear()
                        }
                        "aka" -> {
                            inAka = true
                            akaFirst = null
                            akaLast = null
                        }
                        "dateOfBirthItem" -> inDobItem = true
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    text.append(ch, start, length)
                }

                override fun endElement(uri: String, local: String, qName: String) {
                    val t = text.toString().trim()
                    text.clear()
                    if (!inEntry) return

                    when {
                        inAka && qName == "firstName" -> akaFirst = t.takeIf { it.isNotBlank() }
                        inAka && qName == "lastName" -> akaLast = t.takeIf { it.isNotBlank() }
                        inAka && qName == "aka" -> {
                            val fn = akaFirst ?: ""
                            val ln = akaLast ?: ""
                            val alias = if (fn.isBlank()) ln else "$fn $ln".trim()
                            if (alias.isNotBlank()) aliases += alias
                            inAka = false
                        }
                        !inAka && qName == "firstName" -> if (firstName == null) {
                            firstName = t.takeIf { it.isNotBlank() }
                        }
                        !inAka && qName == "lastName" -> if (lastName == null) {
                            lastName = t.takeIf { it.isNotBlank() }
                        }
                        qName == "uid" && uid == null -> uid = t.takeIf { it.isNotBlank() }
                        qName == "sdnType" -> sdnType = t.takeIf { it.isNotBlank() }
                        qName == "program" -> if (t.isNotBlank()) programs += t
                        inDobItem && qName == "dateOfBirth" -> if (dob == null) {
                            dob = t.takeIf { it.isNotBlank() }
                        }
                        qName == "dateOfBirthItem" -> inDobItem = false
                        qName == "sdnEntry" -> {
                            inEntry = false
                            val u = uid ?: return
                            val fn = firstName ?: ""
                            val ln = lastName ?: ""
                            val pn = if (fn.isBlank()) ln else "$fn $ln".trim()
                            if (pn.isBlank()) return
                            val et = if (sdnType?.lowercase()?.contains("entity") == true) {
                                EntityType.ORGANIZATION
                            } else {
                                EntityType.INDIVIDUAL
                            }
                            allEntries += buildEntry(
                                listType = SanctionsListType.OFAC_SDN,
                                externalId = "ofac-$u",
                                entityType = et,
                                primaryName = pn,
                                aliases = aliases.toList(),
                                dateOfBirth = dob,
                                programs = programs.toList(),
                            )
                        }
                    }
                }
            },
        )

        Log.infof("SAX-parsed %d OFAC SDN entries", allEntries.size)
        var total = 0
        for (chunk in allEntries.chunked(IMPORT_BATCH_SIZE)) {
            total += entryRepo.upsertAll(chunk)
        }
        Log.infof("Upserted %d OFAC SDN entries", total)
        total
    }

    // ──────────────────────────────────────────────────────────────────────────
    // OpenSanctions  targets.simple.csv  (PEP_GLOBAL, EU, UN, HM and others)
    // Streaming: reads line-by-line from InputStream, upserts every BATCH_SIZE.
    //
    // Actual CSV columns (v2 format, same across all datasets):
    //   id, schema, name, aliases, birth_date, countries, addresses, identifiers,
    //   sanctions, phones, emails, program_ids, dataset, first_seen, last_seen, last_change
    //
    // Aliases are semicolon-separated inside a single quoted CSV field.
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun importOpenSanctionsCsv(
        url: String,
        listType: SanctionsListType,
        defaultPrograms: List<String> = listOf("PEP"),
    ): Int {
        val inputStream = withContext(Dispatchers.IO) { httpGetStream(url) }
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

        val headerLine = withContext(Dispatchers.IO) { reader.readLine() } ?: return 0
        val headers = parseCsvLine(headerLine)

        // Resolve column indexes from actual header (format-safe)
        val idxId = headers.indexOf("id")
        val idxSchema = headers.indexOf("schema")
        val idxName = headers.indexOf("name")
        val idxAliases = headers.indexOf("aliases")
        val idxBirthDate = headers.indexOf("birth_date")
        val idxCountries = headers.indexOf("countries")
        val idxProgramIds = headers.indexOf("program_ids")

        if (idxId < 0 || idxName < 0) {
            Log.warnf("Unexpected CSV header for %s — missing 'id' or 'name' column. Headers: %s", listType, headers)
            reader.close()
            return 0
        }

        var total = 0
        val batch = mutableListOf<SanctionsEntry>()
        // Present-set for the end-of-stream reconciliation sweep below — NOT a deactivate-first
        // pass. Deactivating stale entries used to run BEFORE this loop, unconditionally, for
        // every row in the list; that made a failed refresh (network drop mid-stream) leave the
        // whole list wiped with only the partial replacement reactivated, and doubled the WAL
        // cost of every successful refresh by rewriting the still-present majority twice
        // (deactivate, then reactivate on upsert). See #1432.
        val seenExternalIds = mutableSetOf<String>()

        fun col(cols: List<String>, idx: Int) = if (idx >= 0) cols.getOrElse(idx) { "" }.trim() else ""

        try {
            while (true) {
                val rawLine = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                if (rawLine.isBlank()) continue

                val cols = parseCsvLine(rawLine)
                val id = col(cols, idxId)
                val schema = col(cols, idxSchema)
                val name = col(cols, idxName)
                val aliasesRaw = col(cols, idxAliases)
                val birthDate = col(cols, idxBirthDate)
                val countriesRaw = col(cols, idxCountries)
                val programRaw = col(cols, idxProgramIds)

                if (name.isBlank()) continue
                id.takeIf { it.isNotBlank() }?.let { seenExternalIds += it }

                val aliases = aliasesRaw.split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != name }
                    .distinct()

                val nationalities = countriesRaw.split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                val programs = if (programRaw.isNotBlank()) {
                    programRaw.split(";").map { it.trim() }.filter { it.isNotBlank() }
                } else {
                    defaultPrograms
                }

                val entityType = when {
                    schema in listOf("Organization", "Company", "PublicBody", "LegalEntity") -> EntityType.ORGANIZATION
                    schema == "Vessel" -> EntityType.VESSEL
                    schema == "Aircraft" -> EntityType.AIRCRAFT
                    else -> EntityType.INDIVIDUAL
                }

                batch += buildEntry(
                    listType = listType,
                    externalId = id.takeIf { it.isNotBlank() },
                    entityType = entityType,
                    primaryName = name,
                    aliases = aliases,
                    dateOfBirth = birthDate.takeIf { it.isNotBlank() },
                    nationalities = nationalities,
                    programs = programs,
                )

                if (batch.size >= IMPORT_BATCH_SIZE) {
                    total += entryRepo.upsertAll(batch)
                    batch.clear()
                    if (total % 10_000 == 0) Log.infof("OpenSanctions %s: %d entries imported so far…", listType, total)
                }
            }
        } finally {
            reader.close()
        }

        if (batch.isNotEmpty()) total += entryRepo.upsertAll(batch)

        // Only reached if the loop above completed without throwing — a mid-stream failure
        // (network drop, malformed line) propagates out of this function instead, and the
        // existing list is left untouched rather than partially wiped.
        val deactivated = entryRepo.deactivateMissing(listType, seenExternalIds)
        Log.infof(
            "Imported %d OpenSanctions entries for %s (%d no longer present, deactivated)",
            total,
            listType,
            deactivated,
        )
        return total
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Open an HTTP GET request and return the response body as a streaming InputStream. */
    private fun httpGetStream(url: String): InputStream {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(300))
            .header("User-Agent", "OpenBank-SanctionsService/1.0")
            .GET().build()
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream()).body()
    }

    private fun buildEntry(
        listType: SanctionsListType,
        externalId: String?,
        entityType: EntityType,
        primaryName: String,
        aliases: List<String> = emptyList(),
        dateOfBirth: String? = null,
        nationalities: List<String> = emptyList(),
        programs: List<String> = emptyList(),
    ): SanctionsEntry {
        val searchText = (listOf(primaryName) + aliases)
            .map { normalizeForSearch(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
        val now = Instant.now(clock)
        return SanctionsEntry(
            id = UUID.randomUUID(),
            listType = listType, externalId = externalId, entityType = entityType,
            primaryName = primaryName, aliases = aliases, dateOfBirth = dateOfBirth,
            nationalities = nationalities, programs = programs, searchText = searchText,
            createdAt = now, updatedAt = now,
        )
    }

    /** Strip diacritics and lowercase — mirrors what the similarity query receives. */
    fun normalizeForSearch(input: String): String = Normalizer.normalize(input, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
        .trim()

    /** RFC 4180-compatible CSV line parser (handles quoted fields with embedded commas/quotes). */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuote -> inQuote = true
                ch == '"' && inQuote && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                ch == '"' && inQuote -> inQuote = false
                ch == ',' && !inQuote -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        result += current.toString()
        return result
    }

    companion object {
        const val IMPORT_BATCH_SIZE = 500

        /** `openbank.sanctions.eu.source` values: first-party FSF XML (default), the OpenSanctions mirror, or non-production seeds. */
        const val EU_SOURCE_EU_FSF = "eu-fsf"
        const val EU_SOURCE_OPENSANCTIONS = "opensanctions"
        const val EU_SOURCE_SEED = "seed"

        /** The official EU Financial Sanctions Files endpoint (full consolidated list, FSF v1.1). */
        const val DEFAULT_EU_FSF_URL =
            "https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw"

        private val EU_PROGRAMS = listOf("EU-SANCTIONS")
    }
}
