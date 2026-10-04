// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.cnb

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure parser for the ČNB policy-rate history files (`vyvoj_repo_historie.txt`,
 * `vyvoj_diskontni_historie.txt`, `vyvoj_lombard_historie.txt`). Format, UTF-8 with a BOM:
 *
 * ```
 * PLATNA_OD|CNB_REPO_SAZBA_V_%
 * 19951208|11,30
 * 20260619|3,75
 * ```
 *
 * Tolerates the BOM, CRLF or LF, a missing trailing newline, surrounding whitespace and blank
 * lines. Everything else is LOUD: a wrong header (an HTML error page, the wrong file), any data row
 * that does not parse, a duplicated date, or a percentage outside [0, 100] fails the WHOLE file
 * with every bad row counted — a history with one silently skipped row would serve the previous
 * rate for that period, and nothing downstream could tell.
 */
object CnbPolicyRateParser {

    private val DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val ROW = Regex("""^(\d{8})\|(\d{1,3}(?:,\d+)?)$""")
    private val HUNDRED = BigDecimal(100)
    private const val BOM = '﻿'
    private const val MAX_REPORTED = 5

    /** FRACTION scale: the feed carries at most 2 decimals of a percent. */
    private const val FRACTION_SCALE = 8

    fun parse(text: String, instrument: CnbPolicyInstrument): List<CnbPolicyRateObservation> {
        val expected = requireNotNull(instrument.feedHeader) { "$instrument has no machine-readable feed" }
        val lines = text.removePrefix(BOM.toString())
            .lineSequence()
            .map { it.trim() }
            .withIndex()
            .filter { it.value.isNotEmpty() }
            .toList()
        require(lines.isNotEmpty()) { "Empty ČNB $instrument history" }
        require(lines.first().value == expected) {
            "ČNB $instrument history: expected header '$expected', got '${lines.first().value.take(HEADER_PREVIEW)}'"
        }

        val bad = mutableListOf<String>()
        val rows = lines.drop(1).mapNotNull { (index, line) ->
            parseRow(line).also { if (it == null) bad += "line ${index + 1}: '${line.take(HEADER_PREVIEW)}'" }
        }
        require(bad.isEmpty()) {
            "ČNB $instrument history: ${bad.size} malformed row(s), first ${bad.take(MAX_REPORTED)}"
        }
        require(rows.isNotEmpty()) { "ČNB $instrument history contained no rows" }
        val duplicates = rows.groupBy { it.effectiveFrom }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "ČNB $instrument history: duplicated effective dates $duplicates" }
        return rows.sortedBy { it.effectiveFrom }
    }

    private fun parseRow(line: String): CnbPolicyRateObservation? {
        val m = ROW.matchEntire(line) ?: return null
        val date = try {
            LocalDate.parse(m.groupValues[1], DATE)
        } catch (_: DateTimeParseException) {
            return null
        }
        val percent = BigDecimal(m.groupValues[2].replace(',', '.'))
        if (percent > HUNDRED) return null
        return CnbPolicyRateObservation(date, percent.divide(HUNDRED, FRACTION_SCALE, RoundingMode.UNNECESSARY))
    }

    private const val HEADER_PREVIEW = 60
}
