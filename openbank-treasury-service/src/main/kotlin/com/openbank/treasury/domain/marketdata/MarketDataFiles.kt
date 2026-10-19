// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.marketdata

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Bounded import and export of index observations (contract 8.5). CSV is `index,date,value` with an
 * optional header row; JSON is `[{"index":..,"date":..,"value":..}]` (parsed by the caller into the
 * same triples). Every row is validated; the first bad row refuses the whole file (400) — a partial
 * import is never stored.
 */
object MarketDataFiles {
    const val MAX_BYTES = 1_048_576
    const val MAX_ROWS = ObservationBatch.MAX_ROWS
    private const val MAX_FIELD = 64

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun parseCsv(bytes: ByteArray): List<Triple<String, LocalDate, BigDecimal>> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "an import file is 1..$MAX_BYTES bytes" }
        val lines = String(bytes, Charsets.UTF_8).removePrefix("").lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val body = if (lines.firstOrNull()?.lowercase()?.startsWith("index") == true) lines.drop(1) else lines
        require(body.size in 1..MAX_ROWS) { "an import file has 1..$MAX_ROWS rows" }
        return body.mapIndexed { i, line ->
            val cols = line.split(',').map { it.trim().removeSurrounding("\"") }
            require(cols.size == 3) { "row ${i + 1}: expected index,date,value" }
            row(i + 1, cols[0], cols[1], cols[2])
        }
    }

    fun row(n: Int, index: String?, date: String?, value: String?): Triple<String, LocalDate, BigDecimal> {
        require(!index.isNullOrBlank() && index.length <= MAX_FIELD) { "row $n: index is required" }
        require(IndexObservation.INDEX_CODE.matches(index)) { "row $n: index '$index' is not an index code" }
        val d = try {
            LocalDate.parse(requireNotNull(date) { "row $n: date is required" })
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("row $n: date must be ISO yyyy-MM-dd", e)
        }
        require(!value.isNullOrBlank() && value.length <= MAX_FIELD) { "row $n: value is required" }
        val v = value.toBigDecimalOrNull() ?: throw IllegalArgumentException("row $n: value is not a number")
        require(v.abs() <= IndexObservation.MAX_ABS_PERCENT && v.scale() <= IndexObservation.MAX_SCALE) {
            "row $n: value must be a percentage within ±100 with at most ${IndexObservation.MAX_SCALE} decimals"
        }
        return Triple(index, d, v)
    }

    /**
     * CSV export. Every cell is quoted, and a cell a spreadsheet would read as a formula (=, +, -, @,
     * tab, carriage return) is prefixed with a single quote: formula-injection neutralisation.
     */
    fun toCsv(rows: List<IndexObservation>): String = buildString {
        append("index,date,value,source,sha256\n")
        rows.forEach { o ->
            append(
                listOf(o.index, o.date.toString(), o.value.toPlainString(), o.source.name, o.sha256).joinToString(",") {
                    cell(it)
                },
            )
            append('\n')
        }
    }

    internal fun cell(raw: String): String {
        val neutral = if (raw.isNotEmpty() && raw[0] in FORMULA_LEAD && !NUMBER.matches(raw)) "'$raw" else raw
        return "\"" + neutral.replace("\"", "\"\"") + "\""
    }

    private val FORMULA_LEAD = setOf('=', '+', '-', '@', '\t', '\r')
    private val NUMBER = Regex("^-?[0-9]+(\\.[0-9]+)?$")
}
