// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.cnb

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Parses the ČNB minimum-reserve history `PMR_historie_zmen.xlsx` into the reserve RATIO and its
 * REMUNERATION as step functions (only the dates on which the value changed).
 *
 * The file is written for people: one sheet per year (tab name = the year), a header row with the
 * labels `Základna` / `Sazba ze základny` / `Úročení`, and below it one row per change whose first
 * column is the effective date (an Excel date serial). Cells are free Czech text. Nothing here
 * relies on fixed coordinates: the header row is FOUND by its labels on every sheet, and the date
 * column is the one immediately left of `Základna`.
 *
 * What is machine-representable, and therefore stored:
 *  - ratio: a cell carrying exactly ONE `N % ze základny` (e.g. `4 % ze základny  0 % závazky z repo
 *    operací`). The 1990s cells with several ratios (`9.5% ze základny bank, 4% ze základny
 *    stavebních spořitelen…`) are not one number for this bank;
 *  - remuneration: exactly `PMR se úročí úrokovou sazbou ve výši N %`. Earlier wordings reference
 *    another rate (`2T repo sazba`, conditionally floored at 0) and are not a fixed number either.
 *
 * Anything before the FIRST representable value of an instrument is pre-history and simply not
 * stored (the risk engine then answers NOT_EVALUABLE for those dates). Anything AFTER it must be
 * representable, or the whole file is rejected — as is a sheet without its header, a sheet name
 * that is not a year, a date that is not an integer serial or falls outside its sheet's year, dates
 * going backwards, a percentage above 100, or a file that yields no ratio at all. A partial reading
 * of this file would put a wrong number into a regulatory requirement, so it is all or nothing.
 */
object CnbMinimumReserveParser {

    private val EXCEL_EPOCH: LocalDate = LocalDate.parse("1899-12-30") // the Excel (1900 date system) day zero
    private val HUNDRED = BigDecimal.ONE.movePointRight(2)
    private const val FRACTION_SCALE = 8
    private const val LABEL_BASE = "základna"
    private const val LABEL_RATIO = "sazba ze základny"
    private const val LABEL_REMUNERATION = "úročení"
    private val YEAR = Regex("""^\d{4}$""")
    private val SERIAL = Regex("""^\d{1,6}$""")
    private val RATIO = Regex("""(\d{1,3}(?:[.,]\d{1,6})?)\s*%\s*ze\s+základny""", RegexOption.IGNORE_CASE)
    private val REMUNERATION =
        Regex("""^PMR se úročí úrokovou sazbou ve výši (\d{1,3}(?:[.,]\d{1,6})?)\s*%$""", RegexOption.IGNORE_CASE)
    private val CELL_REF = Regex("""^([A-Z]{1,3})(\d{1,7})$""")

    data class Result(val ratio: List<CnbPolicyRateObservation>, val remuneration: List<CnbPolicyRateObservation>)

    fun parse(bytes: ByteArray): Result = parse(SafeXlsxReader.read(bytes))

    fun parse(sheets: List<SafeXlsxReader.Sheet>): Result {
        require(sheets.isNotEmpty()) { "ČNB minimum-reserve history: workbook has no sheets" }
        val ratio =
            Series(CnbPolicyInstrument.MIN_RESERVE_RATIO) { RATIO.findAll(it).map { m -> m.groupValues[1] }.toList() }
        val remuneration = Series(CnbPolicyInstrument.MIN_RESERVE_REMUNERATION) {
            listOfNotNull(REMUNERATION.matchEntire(it)?.groupValues?.get(1))
        }
        var lastDate: LocalDate? = null
        for (sheet in sheets) {
            val year = requireNotNull(sheet.name.trim().takeIf { YEAR.matches(it) }?.toInt()) {
                "ČNB minimum-reserve history: sheet '${sheet.name}' is not named by a year"
            }
            val header = Header.find(sheet)
            for ((row, dateCell) in header.dateCells(sheet)) {
                val raw = dateCell.number ?: continue // a text note ("V roce X nedošlo k žádné změně PMR")
                require(SERIAL.matches(raw)) { "sheet $year row $row: date '$raw' is not a whole Excel date serial" }
                val date = EXCEL_EPOCH.plusDays(raw.toLong())
                require(date.year == year) { "sheet $year row $row: date $date is outside the sheet's year" }
                lastDate?.let { require(date.isAfter(it)) { "sheet $year row $row: date $date does not follow $it" } }
                lastDate = date
                sheet.cells["${header.ratioCol}$row"]?.text?.let { ratio.offer(date, it, "sheet $year row $row") }
                sheet.cells["${header.remunerationCol}$row"]?.text?.let {
                    remuneration.offer(date, it, "sheet $year row $row")
                }
            }
        }
        require(ratio.values.isNotEmpty()) { "ČNB minimum-reserve history: no representable reserve ratio found" }
        return Result(ratio.values, remuneration.values)
    }

    /** The header row of one sheet, located by its labels. */
    private class Header(val row: Int, val dateCol: String, val ratioCol: String, val remunerationCol: String) {
        fun dateCells(sheet: SafeXlsxReader.Sheet): List<Pair<Int, SafeXlsxReader.Cell>> =
            sheet.cells.mapNotNull { (ref, cell) ->
                val (col, r) = split(ref)
                if (col == dateCol && r > row) r to cell else null
            }.sortedBy { it.first }

        companion object {
            fun find(sheet: SafeXlsxReader.Sheet): Header {
                val labelled = sheet.cells.mapNotNull { (ref, cell) -> cell.text?.let { normalise(it) to split(ref) } }
                fun at(label: String) = labelled.filter { it.first == label }.map { it.second }
                val base = at(LABEL_BASE).singleOrNull()
                val ratio = at(LABEL_RATIO).singleOrNull()
                val rem = at(LABEL_REMUNERATION).singleOrNull()
                require(base != null && ratio != null && rem != null) {
                    "ČNB minimum-reserve history: sheet '${sheet.name}' has no single header row with " +
                        "'Základna' / 'Sazba ze základny' / 'Úročení'"
                }
                require(base.second == ratio.second && ratio.second == rem.second) {
                    "ČNB minimum-reserve history: sheet '${sheet.name}' header labels are not on one row"
                }
                require(base.first != "A") { "ČNB minimum-reserve history: sheet '${sheet.name}' has no date column" }
                return Header(base.second, previousColumn(base.first), ratio.first, rem.first)
            }
        }
    }

    /** One instrument's change history: pre-history until the first representable value, then strict. */
    private class Series(val instrument: CnbPolicyInstrument, val extract: (String) -> List<String>) {
        val values = mutableListOf<CnbPolicyRateObservation>()

        fun offer(date: LocalDate, text: String, where: String) {
            val found = extract(normalise(text))
            if (found.size != 1) {
                require(values.isEmpty()) {
                    "$where: $instrument '${text.take(PREVIEW)}' is not a single rate after the history began"
                }
                return
            }
            val percent = BigDecimal(found.single().replace(',', '.'))
            require(percent <= HUNDRED) { "$where: $instrument $percent % is above 100 %" }
            val rate = percent.divide(HUNDRED, FRACTION_SCALE, RoundingMode.UNNECESSARY)
            if (values.lastOrNull()?.rate?.compareTo(rate) != 0) values += CnbPolicyRateObservation(date, rate)
        }
    }

    private const val PREVIEW = 60

    /** Lower-case with every run of whitespace (the file pads with many spaces and NBSPs) collapsed. */
    private fun normalise(s: String): String = s.replace(' ', ' ').trim().replace(Regex("""\s+"""), " ").lowercase()

    private fun split(ref: String): Pair<String, Int> {
        val m = requireNotNull(CELL_REF.matchEntire(ref)) { "malformed cell reference '$ref'" }
        return m.groupValues[1] to m.groupValues[2].toInt()
    }

    private fun previousColumn(col: String): String {
        var n = col.fold(0) { acc, c -> acc * ALPHABET + (c - 'A' + 1) } - 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % ALPHABET
            sb.append('A' + rem)
            n = (n - 1) / ALPHABET
        }
        return sb.reverse().toString()
    }

    private const val ALPHABET = 26
}
