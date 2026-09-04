// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.out.SanctionsEntryRepository
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.SanctionsEntry
import com.openbank.sanctions.domain.model.SanctionsListChangeSet
import com.openbank.sanctions.domain.model.SanctionsListType
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 *  - PEP_GLOBAL / EU / UN / HM → OpenSanctions targets.simple.csv — BufferedReader streaming, batch upsert
 *  - FATF         → country-risk, not entity-based — skipped
 *  - CNB          → no machine-readable feed — seeded in Flyway V6
 *
 * Called by [SanctionsListService.refresh].
 * Memory design: never buffers the full HTTP body as String; parses from InputStream.
 */
@ApplicationScoped
class SanctionsImportService(private val entryRepo: SanctionsEntryRepository, private val clock: Clock) {

    // CDI entry point: injects the production UTC clock. Tests use the primary constructor with a
    // fixed Clock for deterministic timestamps (ADR-0100 Layer 1).
    @Inject
    constructor(entryRepo: SanctionsEntryRepository) : this(entryRepo, Clock.systemUTC())

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    /**
     * Downloads and imports [listType] from [sourceUrl].
     * @return the content-level change set (which entries were written/changed vs dropped) so a
     *   caller can fire a `SANCTIONS_LIST_CHANGED` trigger (ADR-0256 D1); an empty change set
     *   means the import was content-identical and nothing is raised. The [SanctionsListChangeSet.changeCount]
     *   is also the "entries upserted" count legacy callers used to read from the Int return.
     */
    suspend fun importList(listType: SanctionsListType, sourceUrl: String): SanctionsListChangeSet {
        Log.infof("Importing %s from %s", listType, sourceUrl)
        return try {
            when (listType) {
                // OFAC SDN: official Treasury XML — SAX streaming
                SanctionsListType.OFAC_SDN -> importOfacSdn(sourceUrl)
                // OpenSanctions-normalised CSV for PEP, EU, UN, HM — stream line-by-line
                SanctionsListType.PEP_GLOBAL -> importOpenSanctionsCsv(sourceUrl, listType, listOf("PEP"))
                SanctionsListType.EU_CONSOLIDATED -> importOpenSanctionsCsv(sourceUrl, listType, listOf("EU-SANCTIONS"))
                SanctionsListType.UN_CONSOLIDATED -> importOpenSanctionsCsv(sourceUrl, listType, listOf("UN-SANCTIONS"))
                SanctionsListType.HM_TREASURY -> importOpenSanctionsCsv(sourceUrl, listType, listOf("HMT-SANCTIONS"))
                SanctionsListType.FATF_HIGH_RISK -> {
                    Log.info("FATF is country-risk — skipping import")
                    SanctionsListChangeSet(listType)
                }
                SanctionsListType.CNB_DOMESTIC -> {
                    Log.info("CNB has no machine-readable feed — entries seeded via migration")
                    SanctionsListChangeSet(listType)
                }
            }
        } catch (ex: Exception) {
            Log.warnf(
                "Import failed for %s (%s: %s) — keeping existing entries",
                listType,
                ex.javaClass.simpleName,
                ex.message,
            )
            SanctionsListChangeSet(listType)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // OFAC SDN  (sdn.xml) — SAX streaming, O(1) peak memory per entry
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun importOfacSdn(url: String): SanctionsListChangeSet = withContext(Dispatchers.IO) {
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
        val changed = mutableSetOf<String>()
        for (chunk in allEntries.chunked(IMPORT_BATCH_SIZE)) {
            changed += entryRepo.upsertAllReturningChanged(chunk)
        }
        // OFAC SDN deliberately runs no deactivation sweep — unlike the OpenSanctions CSV path,
        // it never has, and adding one is a behavioural change beyond diff detection.
        Log.infof("Upserted %d OFAC SDN entries (%d changed)", allEntries.size, changed.size)
        SanctionsListChangeSet(SanctionsListType.OFAC_SDN, changedExternalIds = changed)
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
    ): SanctionsListChangeSet {
        val inputStream = withContext(Dispatchers.IO) { httpGetStream(url) }
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

        val headerLine = withContext(Dispatchers.IO) { reader.readLine() } ?: return SanctionsListChangeSet(listType)
        val headers = OpenSanctionsCsvColumns.parseCsvLine(headerLine)

        val idx = OpenSanctionsCsvColumns.from(headers)
        if (idx == null) {
            Log.warnf("Unexpected CSV header for %s — missing 'id' or 'name' column. Headers: %s", listType, headers)
            reader.close()
            return SanctionsListChangeSet(listType)
        }

        val changed = mutableSetOf<String>()
        // Present-set for the end-of-stream reconciliation sweep below — NOT a deactivate-first
        // pass. Deactivating stale entries used to run BEFORE the read loop, unconditionally, for
        // every row in the list; that made a failed refresh (network drop mid-stream) leave the
        // whole list wiped with only the partial replacement reactivated, and doubled the WAL
        // cost of every successful refresh by rewriting the still-present majority twice
        // (deactivate, then reactivate on upsert). See #1432.
        val seenExternalIds = mutableSetOf<String>()
        val total = streamBatches(reader, idx, listType, defaultPrograms, seenExternalIds, changed)

        // Only reached if the stream completed without throwing — a mid-stream failure (network
        // drop, malformed line) propagates out of streamBatches instead, and the existing list is
        // left untouched rather than partially wiped.
        val deactivated = entryRepo.deactivateMissingReturning(listType, seenExternalIds)
        Log.infof(
            "Imported %d OpenSanctions entries for %s (%d changed, %d no longer present, deactivated)",
            total,
            listType,
            changed.size,
            deactivated.size,
        )
        return SanctionsListChangeSet(listType, changed, deactivated)
    }

    /** Read the whole CSV body, upserting in [IMPORT_BATCH_SIZE] chunks; returns rows read. */
    private suspend fun streamBatches(
        reader: BufferedReader,
        idx: OpenSanctionsCsvColumns,
        listType: SanctionsListType,
        defaultPrograms: List<String>,
        seenExternalIds: MutableSet<String>,
        changed: MutableSet<String>,
    ): Int {
        var total = 0
        val batch = mutableListOf<SanctionsEntry>()
        try {
            while (true) {
                val rawLine = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                val entry = parseOpenSanctionsRow(rawLine, idx, listType, defaultPrograms) ?: continue
                entry.externalId?.let { seenExternalIds += it }
                batch += entry
                if (batch.size >= IMPORT_BATCH_SIZE) total += flushBatch(batch, changed)
            }
        } finally {
            withContext(Dispatchers.IO) { reader.close() }
        }
        total += flushBatch(batch, changed)
        if (total % 10_000 == 0 || total >= 10_000) Log.infof("OpenSanctions %s: %d entries imported", listType, total)
        return total
    }

    /** Upsert [batch] into the store, accumulating its changed ids into [changed]; returns rows flushed. */
    private suspend fun flushBatch(batch: MutableList<SanctionsEntry>, changed: MutableSet<String>): Int {
        if (batch.isEmpty()) return 0
        changed += entryRepo.upsertAllReturningChanged(batch)
        val flushed = batch.size
        batch.clear()
        return flushed
    }

    private fun parseOpenSanctionsRow(
        rawLine: String,
        idx: OpenSanctionsCsvColumns,
        listType: SanctionsListType,
        defaultPrograms: List<String>,
    ): SanctionsEntry? {
        val parsed = idx.parseRow(rawLine) ?: return null
        return buildEntry(
            listType = listType,
            externalId = parsed.id,
            entityType = parsed.entityType,
            primaryName = parsed.name,
            aliases = parsed.aliases,
            dateOfBirth = parsed.birthDate,
            nationalities = parsed.nationalities,
            programs = parsed.programs.ifEmpty { defaultPrograms },
        )
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

    companion object {
        const val IMPORT_BATCH_SIZE = 500
    }
}
