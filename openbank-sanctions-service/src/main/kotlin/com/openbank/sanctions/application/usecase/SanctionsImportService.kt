// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.out.SanctionsEntryRepository
import com.openbank.sanctions.domain.model.*
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
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
import java.time.Duration
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
class SanctionsImportService(
    private val entryRepo: SanctionsEntryRepository,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    /**
     * Downloads and imports [listType] from [sourceUrl].
     * @return number of entries upserted (0 if format not yet implemented).
     */
    suspend fun importList(listType: SanctionsListType, sourceUrl: String): Int {
        Log.infof("Importing %s from %s", listType, sourceUrl)
        return try {
            when (listType) {
                // OFAC SDN: official Treasury XML — SAX streaming
                SanctionsListType.OFAC_SDN       -> importOfacSdn(sourceUrl)
                // OpenSanctions-normalised CSV for PEP, EU, UN, HM — stream line-by-line
                SanctionsListType.PEP_GLOBAL      -> importOpenSanctionsCsv(sourceUrl, listType, listOf("PEP"))
                SanctionsListType.EU_CONSOLIDATED -> importOpenSanctionsCsv(sourceUrl, listType, listOf("EU-SANCTIONS"))
                SanctionsListType.UN_CONSOLIDATED -> importOpenSanctionsCsv(sourceUrl, listType, listOf("UN-SANCTIONS"))
                SanctionsListType.HM_TREASURY     -> importOpenSanctionsCsv(sourceUrl, listType, listOf("HMT-SANCTIONS"))
                SanctionsListType.FATF_HIGH_RISK  -> { Log.info("FATF is country-risk — skipping import"); 0 }
                SanctionsListType.CNB_DOMESTIC    -> { Log.info("CNB has no machine-readable feed — entries seeded via migration"); 0 }
            }
        } catch (ex: Exception) {
            Log.warnf("Import failed for %s (%s: %s) — keeping existing entries",
                      listType, ex.javaClass.simpleName, ex.message)
            0
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // OFAC SDN  (sdn.xml) — SAX streaming, O(1) peak memory per entry
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun importOfacSdn(url: String): Int = withContext(Dispatchers.IO) {
        val inputStream = httpGetStream(url)
        val allEntries = mutableListOf<SanctionsEntry>()

        val saxFactory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        saxFactory.newSAXParser().parse(inputStream, object : DefaultHandler() {
            private var inEntry    = false
            private var inAka      = false
            private var inDobItem  = false
            private val text       = StringBuilder()

            private var uid: String?      = null
            private var firstName: String? = null
            private var lastName: String?  = null
            private var sdnType: String?   = null
            private val programs = mutableListOf<String>()
            private val aliases  = mutableListOf<String>()
            private var dob: String?      = null

            private var akaFirst: String? = null
            private var akaLast: String?  = null

            override fun startElement(uri: String, local: String, qName: String, attrs: Attributes) {
                text.clear()
                when (qName) {
                    "sdnEntry" -> {
                        inEntry = true
                        uid = null; firstName = null; lastName = null; sdnType = null; dob = null
                        programs.clear(); aliases.clear()
                    }
                    "aka"             -> { inAka = true; akaFirst = null; akaLast = null }
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
                    inAka && qName == "lastName"  -> akaLast  = t.takeIf { it.isNotBlank() }
                    inAka && qName == "aka" -> {
                        val fn = akaFirst ?: ""; val ln = akaLast ?: ""
                        val alias = if (fn.isBlank()) ln else "$fn $ln".trim()
                        if (alias.isNotBlank()) aliases += alias
                        inAka = false
                    }
                    !inAka && qName == "firstName"     -> if (firstName == null) firstName = t.takeIf { it.isNotBlank() }
                    !inAka && qName == "lastName"      -> if (lastName  == null) lastName  = t.takeIf { it.isNotBlank() }
                    qName == "uid"     && uid == null  -> uid     = t.takeIf { it.isNotBlank() }
                    qName == "sdnType"                 -> sdnType = t.takeIf { it.isNotBlank() }
                    qName == "program"                 -> if (t.isNotBlank()) programs += t
                    inDobItem && qName == "dateOfBirth" -> if (dob == null) dob = t.takeIf { it.isNotBlank() }
                    qName == "dateOfBirthItem"          -> inDobItem = false
                    qName == "sdnEntry" -> {
                        inEntry = false
                        val u  = uid ?: return
                        val fn = firstName ?: ""; val ln = lastName ?: ""
                        val pn = if (fn.isBlank()) ln else "$fn $ln".trim()
                        if (pn.isBlank()) return
                        val et = if (sdnType?.lowercase()?.contains("entity") == true)
                            EntityType.ORGANIZATION else EntityType.INDIVIDUAL
                        allEntries += buildEntry(
                            listType    = SanctionsListType.OFAC_SDN,
                            externalId  = "ofac-$u",
                            entityType  = et,
                            primaryName = pn,
                            aliases     = aliases.toList(),
                            dateOfBirth = dob,
                            programs    = programs.toList(),
                        )
                    }
                }
            }
        })

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
        val idxId         = headers.indexOf("id")
        val idxSchema     = headers.indexOf("schema")
        val idxName       = headers.indexOf("name")
        val idxAliases    = headers.indexOf("aliases")
        val idxBirthDate  = headers.indexOf("birth_date")
        val idxCountries  = headers.indexOf("countries")
        val idxProgramIds = headers.indexOf("program_ids")

        if (idxId < 0 || idxName < 0) {
            Log.warnf("Unexpected CSV header for %s — missing 'id' or 'name' column. Headers: %s", listType, headers)
            reader.close()
            return 0
        }

        // Deactivate stale entries before streaming new data in
        entryRepo.deactivateByListType(listType)

        var total = 0
        val batch = mutableListOf<SanctionsEntry>()

        fun col(cols: List<String>, idx: Int) = if (idx >= 0) cols.getOrElse(idx) { "" }.trim() else ""

        try {
            while (true) {
                val rawLine = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                if (rawLine.isBlank()) continue

                val cols = parseCsvLine(rawLine)
                val id          = col(cols, idxId)
                val schema      = col(cols, idxSchema)
                val name        = col(cols, idxName)
                val aliasesRaw  = col(cols, idxAliases)
                val birthDate   = col(cols, idxBirthDate)
                val countriesRaw = col(cols, idxCountries)
                val programRaw  = col(cols, idxProgramIds)

                if (name.isBlank()) continue

                val aliases = aliasesRaw.split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != name }
                    .distinct()

                val nationalities = countriesRaw.split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                val programs = if (programRaw.isNotBlank())
                    programRaw.split(";").map { it.trim() }.filter { it.isNotBlank() }
                else
                    defaultPrograms

                val entityType = when {
                    schema in listOf("Organization", "Company", "PublicBody", "LegalEntity") -> EntityType.ORGANIZATION
                    schema == "Vessel"   -> EntityType.VESSEL
                    schema == "Aircraft" -> EntityType.AIRCRAFT
                    else                 -> EntityType.INDIVIDUAL
                }

                batch += buildEntry(
                    listType      = listType,
                    externalId    = id.takeIf { it.isNotBlank() },
                    entityType    = entityType,
                    primaryName   = name,
                    aliases       = aliases,
                    dateOfBirth   = birthDate.takeIf { it.isNotBlank() },
                    nationalities = nationalities,
                    programs      = programs,
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
        Log.infof("Imported %d OpenSanctions entries for %s", total, listType)
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
        return SanctionsEntry(
            id = UUID.randomUUID(),
            listType = listType, externalId = externalId, entityType = entityType,
            primaryName = primaryName, aliases = aliases, dateOfBirth = dateOfBirth,
            nationalities = nationalities, programs = programs, searchText = searchText,
        )
    }

    /** Strip diacritics and lowercase — mirrors what the similarity query receives. */
    fun normalizeForSearch(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFD)
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
                ch == '"' && inQuote && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                ch == '"' && inQuote  -> inQuote = false
                ch == ',' && !inQuote -> { result += current.toString(); current.clear() }
                else                  -> current.append(ch)
            }
            i++
        }
        result += current.toString()
        return result
    }

    companion object {
        const val IMPORT_BATCH_SIZE = 500
    }
}
