// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.cnb

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Pure, total parser for the ČNB daily central-bank fixing text feed (the official
 * `…/central-bank-exchange-rate-fixing/daily.txt`). Format:
 *
 * ```
 * 30.05.2026 #104
 * země|měna|množství|kód|kurz
 * Austrálie|dolar|1|AUD|15,123
 * EMU|euro|1|EUR|25,145
 * Japonsko|jen|100|JPY|14,621
 * ```
 *
 * Line 1 is `DD.MM.YYYY #seq`. Line 2 is a `|`-separated header. Each data line is
 * `country|currency|amount|code|rate`, the rate written with a decimal comma. The parser is
 * side-effect-free and tolerates blank lines, surrounding whitespace, and a header in any
 * language (it is identified by a non-numeric amount field).
 */
object CnbFixingParser {

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun parse(text: String): CnbFixing {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        require(lines.isNotEmpty()) { "Empty ČNB fixing feed" }

        val (date, sequence) = parseHeader(lines.first())

        val rates = lines.asSequence()
            .drop(1)
            .mapNotNull { parseRateLine(it) }
            .toList()
        require(rates.isNotEmpty()) { "ČNB fixing feed contained no rate lines" }

        return CnbFixing(date, sequence, rates)
    }

    private fun parseHeader(line: String): Pair<LocalDate, Int?> {
        // "30.05.2026 #104"  →  date + optional sequence
        val parts = line.split(Regex("\\s+"))
        val date = runCatching { LocalDate.parse(parts[0], DATE) }
            .getOrElse { throw IllegalArgumentException("Unparseable ČNB fixing date header: '$line'", it) }
        val sequence = parts.firstOrNull { it.startsWith("#") }
            ?.removePrefix("#")
            ?.toIntOrNull()
        return date to sequence
    }

    private fun parseRateLine(line: String): CnbFixingRate? {
        val cols = line.split('|')
        if (cols.size != 5) return null
        val amount = cols[2].trim().toIntOrNull() ?: return null // header / non-data line
        val code = cols[3].trim().uppercase()
        if (code.length != 3) return null
        val rate = cols[4].trim().replace(',', '.').toBigDecimalOrNull() ?: return null
        return CnbFixingRate(code = code, amount = amount, rate = rate)
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
}
