// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.importer

import com.openbank.sanctions.domain.model.EntityType
import java.io.InputStream
import java.time.LocalDate

internal data class CzAntiTerrorismDesignation(
    val fragmentId: String,
    val entityType: EntityType,
    val primaryName: String,
    val aliases: List<String>,
    val dateOfBirth: String? = null,
)

/** Parses the official e-Sbírka SPARQL CSV for Regulation No. 210/2008 Coll. */
internal object CzAntiTerrorismCsvParser {
    private val designationPattern = Regex(".*/cast_([12])/bod_[0-9]+$")
    private val leadingNumber = Regex("^\\s*[0-9]+\\.\\s*")
    private val birthDate = Regex("narozen[a]?\\s+dne\\s+([0-9]{1,2})\\.([0-9]{1,2})\\.([0-9]{4})")
    private val quotedName = Regex("„([^“]+)“")

    fun parse(input: InputStream): List<CzAntiTerrorismDesignation> {
        val result = mutableListOf<CzAntiTerrorismDesignation>()
        val fragmentIds = mutableSetOf<String>()
        input.bufferedReader().use { reader ->
            val header = StreamingCsv.readRecord(reader) ?: return emptyList()
            val columns = StreamingCsv.parseRecord(header)
            val designationIndex = columns.indexOf("designation")
            val fragmentIndex = columns.indexOf("fragment")
            val textIndex = columns.indexOf("text")
            require(designationIndex >= 0 && fragmentIndex >= 0 && textIndex >= 0) {
                "Czech legal snapshot is missing designation, fragment or text columns"
            }

            result += generateSequence { StreamingCsv.readRecord(reader) }
                .filter { it.isNotBlank() }
                .mapNotNull {
                    parseDesignation(it, designationIndex, fragmentIndex, textIndex, fragmentIds)
                }
                .toList()
        }
        return result
    }

    private fun parseDesignation(
        record: String,
        designationIndex: Int,
        fragmentIndex: Int,
        textIndex: Int,
        fragmentIds: MutableSet<String>,
    ): CzAntiTerrorismDesignation? {
        val values = StreamingCsv.parseRecord(record)
        val designation = values.getOrElse(designationIndex) { "" }
        val section = designationPattern.matchEntire(designation)?.groupValues?.get(1) ?: return null
        val fragmentId = values.getOrElse(fragmentIndex) { "" }.substringAfterLast('/')
        require(fragmentId.isNotEmpty() && fragmentId.all(Char::isDigit)) {
            "Czech designation has no numeric fragment identity"
        }
        require(fragmentIds.add(fragmentId)) { "Duplicate Czech fragment identity: $fragmentId" }
        val legalText = values.getOrElse(textIndex) { "" }
            .replace("</?var>".toRegex(), "")
            .replace(leadingNumber, "")
            .trim()
        return when (section) {
            "1" -> person(fragmentId, legalText)
            else -> organization(fragmentId, legalText)
        }
    }

    private fun person(fragmentId: String, legalText: String): CzAntiTerrorismDesignation {
        val legalName = legalText.substringBefore(", narozen").trim()
        val nameParts = legalName.split(',', limit = 2)
        require(nameParts.size == 2 && nameParts.all { it.isNotBlank() }) {
            "Unrecognised Czech individual designation: $fragmentId"
        }
        val birth = requireNotNull(birthDate.find(legalText)) {
            "Czech individual $fragmentId has no birth date"
        }
        val (day, month, year) = birth.groupValues.drop(1).map(String::toInt)
        val isoDate = LocalDate.of(year, month, day).toString()
        return CzAntiTerrorismDesignation(
            fragmentId = fragmentId,
            entityType = EntityType.INDIVIDUAL,
            primaryName = "${nameParts[1].trim()} ${nameParts[0].trim()}",
            aliases = listOf(legalName),
            dateOfBirth = isoDate,
        )
    }

    private fun organization(fragmentId: String, legalText: String): CzAntiTerrorismDesignation {
        val names = quotedName.findAll(legalText).map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }.distinct().toList()
        require(names.isNotEmpty()) { "Czech organization $fragmentId has no quoted legal name" }
        return CzAntiTerrorismDesignation(
            fragmentId = fragmentId,
            entityType = EntityType.ORGANIZATION,
            primaryName = names.first(),
            aliases = names.drop(1),
        )
    }
}
