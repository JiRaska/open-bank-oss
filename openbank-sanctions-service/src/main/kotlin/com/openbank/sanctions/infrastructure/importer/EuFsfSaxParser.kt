// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.importer

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * One parsed `sanctionEntity` from the official EU Financial Sanctions File (FSF) —
 * the first-party source of the EU consolidated sanctions list (issue #8362).
 */
data class EuFsfEntity(
    /** The FSF `logicalId` — the stable identity of the entity across list generations. */
    val logicalId: String,
    /** FSF subject type code: `person` | `enterprise` (anything else is kept verbatim). */
    val subjectType: String,
    val primaryName: String,
    val aliases: List<String>,
    val dateOfBirth: String?,
    val nationalities: List<String>,
    val programmes: List<String>,
)

/**
 * SAX-streaming parser for the official EU FSF XML (`xmlFullSanctionsList`, namespace
 * `http://eu.europa.ec/fpi/fsd/export`), O(1) peak memory per entity — the file is ~25 MB and
 * growing, so it is never buffered as a DOM or a String.
 *
 * Element model used (all attributes, no element text except `remark` which is not imported):
 *  - `sanctionEntity` — `logicalId` (stable id), `euReferenceNumber`
 *  - `subjectType` — `code` = `person` | `enterprise`
 *  - `nameAlias` — `wholeName` (or `firstName`/`middleName`/`lastName`), `strong="true"` marks
 *    the primary/legal designation; every further alias is an alias (this is how the FSF carries
 *    spelling variants and AKAs)
 *  - `birthdate` — `birthdate` (ISO `yyyy-MM-dd`; partial dates like year-only are ignored, a
 *    wrong-shaped date must never block an import of the name)
 *  - `citizenship` — `countryIso2Code`
 *  - `regulation` — `programme` (e.g. `IRQ`, `TAQA`)
 *
 * The parser is namespace-agnostic on purpose: it matches on local names (`qName` with
 * namespace processing off), so a future FSF schema revision that adds a prefix does not break
 * the import.
 */
object EuFsfSaxParser {

    fun parse(input: InputStream): List<EuFsfEntity> {
        val handler = FsfHandler()
        SAXParserFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newSAXParser()
            .parse(input, handler)
        return handler.entities
    }

    /** Accumulates one `sanctionEntity` at a time; [entities] holds the finished ones. */
    private class FsfHandler : DefaultHandler() {
        val entities = mutableListOf<EuFsfEntity>()

        private var inEntity = false
        private var logicalId: String? = null
        private var subjectType: String = ""
        private val names = mutableListOf<Pair<String, Boolean>>() // (name, strong)
        private val nationalities = mutableListOf<String>()
        private val programmes = mutableListOf<String>()
        private var dateOfBirth: String? = null

        override fun startElement(uri: String, local: String, qName: String, attrs: Attributes) {
            when (qName) {
                "sanctionEntity" -> onEntityStart(attrs)
                "subjectType" -> onSubjectType(attrs)
                "nameAlias" -> onNameAlias(attrs)
                "birthdate" -> onBirthdate(attrs)
                "citizenship" -> onCitizenship(attrs)
                "regulation" -> onRegulation(attrs)
            }
        }

        private fun onSubjectType(attrs: Attributes) {
            if (!inEntity || subjectType.isNotEmpty()) return
            subjectType = attrs.getValue("code")?.trim().orEmpty()
        }

        private fun onBirthdate(attrs: Attributes) {
            if (!inEntity || dateOfBirth != null) return
            dateOfBirth = attrs.getValue("birthdate").nonBlank()
                ?.takeIf { ISO_DATE.matches(it) }
        }

        private fun onCitizenship(attrs: Attributes) {
            if (!inEntity) return
            attrs.getValue("countryIso2Code").nonBlank()?.let { nationalities += it }
        }

        private fun onRegulation(attrs: Attributes) {
            if (!inEntity) return
            attrs.getValue("programme").nonBlank()?.let { if (it !in programmes) programmes += it }
        }

        private fun onEntityStart(attrs: Attributes) {
            inEntity = true
            logicalId = attrs.getValue("logicalId").nonBlank()
            subjectType = ""
            names.clear()
            nationalities.clear()
            programmes.clear()
            dateOfBirth = null
        }

        private fun onNameAlias(attrs: Attributes) {
            if (!inEntity) return
            val whole = attrs.getValue("wholeName").nonBlank()
            val composed = listOfNotNull(
                attrs.getValue("firstName").nonBlank(),
                attrs.getValue("middleName").nonBlank(),
                attrs.getValue("lastName").nonBlank(),
            ).joinToString(" ").takeIf { it.isNotBlank() }
            val name = whole ?: composed ?: return
            names += name to (attrs.getValue("strong")?.trim() == "true")
        }

        override fun endElement(uri: String, local: String, qName: String) {
            if (qName != "sanctionEntity" || !inEntity) return
            inEntity = false
            val id = logicalId ?: return
            // Primary = the first STRONG designation; a malformed entity with no strong name
            // falls back to its first alias rather than being dropped — the EU list does
            // occasionally carry entities whose only names are non-strong variants.
            val primary = names.firstOrNull { it.second }?.first ?: names.firstOrNull()?.first ?: return
            val aliases = names.map { it.first }.filter { it != primary }.distinct()
            entities += EuFsfEntity(
                logicalId = id,
                subjectType = subjectType.ifEmpty { "person" },
                primaryName = primary,
                aliases = aliases,
                dateOfBirth = dateOfBirth,
                nationalities = nationalities.distinct(),
                programmes = programmes.toList(),
            )
        }
    }

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
}
