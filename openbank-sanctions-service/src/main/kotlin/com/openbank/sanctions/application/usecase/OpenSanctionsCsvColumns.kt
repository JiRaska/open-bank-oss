// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.domain.model.EntityType

/**
 * Resolved column indexes for the OpenSanctions targets.simple.csv v2 header, plus the row
 * parser. Kept top-level (not nested in `SanctionsImportService`) so the import service stays
 * under the function-count threshold and this parser is unit-testable in isolation.
 */
class OpenSanctionsCsvColumns private constructor(
    private val id: Int,
    private val schema: Int,
    private val name: Int,
    private val aliases: Int,
    private val birthDate: Int,
    private val countries: Int,
    private val programIds: Int,
) {

    data class ParsedRow(
        val id: String?,
        val name: String,
        val aliases: List<String>,
        val birthDate: String?,
        val nationalities: List<String>,
        val programs: List<String>,
        val entityType: EntityType,
    )

    /** Map one CSV line to a [ParsedRow]; null when the row is blank or carries no usable name. */
    fun parseRow(rawLine: String): ParsedRow? {
        if (rawLine.isBlank()) return null
        val cols = parseCsvLine(rawLine)
        val rowName = col(cols, name)
        if (rowName.isBlank()) return null

        val aliases = splitSemicolonList(col(cols, aliases)).filter { it != rowName }.distinct()
        val nationalities = splitSemicolonList(col(cols, countries))
        val programRaw = col(cols, programIds)
        val programs = if (programRaw.isNotBlank()) splitSemicolonList(programRaw) else emptyList()
        val entityType = when (col(cols, schema)) {
            "Organization", "Company", "PublicBody", "LegalEntity" -> EntityType.ORGANIZATION
            "Vessel" -> EntityType.VESSEL
            "Aircraft" -> EntityType.AIRCRAFT
            else -> EntityType.INDIVIDUAL
        }

        return ParsedRow(
            id = col(cols, id).takeIf { it.isNotBlank() },
            name = rowName,
            aliases = aliases,
            birthDate = col(cols, birthDate).takeIf { it.isNotBlank() },
            nationalities = nationalities,
            programs = programs,
            entityType = entityType,
        )
    }

    private fun col(cols: List<String>, i: Int) = if (i >= 0) cols.getOrElse(i) { "" }.trim() else ""

    private fun splitSemicolonList(raw: String): List<String> =
        raw.split(";").map { it.trim() }.filter { it.isNotBlank() }

    companion object {
        /** Resolve indexes from the actual header line; null when `id` or `name` is missing. */
        fun from(headers: List<String>): OpenSanctionsCsvColumns? {
            val id = headers.indexOf("id")
            val name = headers.indexOf("name")
            if (id < 0 || name < 0) return null
            return OpenSanctionsCsvColumns(
                id = id,
                schema = headers.indexOf("schema"),
                name = name,
                aliases = headers.indexOf("aliases"),
                birthDate = headers.indexOf("birth_date"),
                countries = headers.indexOf("countries"),
                programIds = headers.indexOf("program_ids"),
            )
        }

        /** RFC 4180-compatible CSV line parser (handles quoted fields with embedded commas/quotes). */
        fun parseCsvLine(line: String): List<String> {
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
    }
}
