// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import kotlin.math.abs

/**
 * SWIFT MT940 customer statement, mapped onto the SAME [NostroStatement] a camt.053 produces
 * (ADR-0315 D7: "camt.053, with MT940 mapped to it"). Pure: no framework, no I/O. The input is an
 * upload from a person, so every malformed shape is an [IllegalArgumentException] (400).
 *
 * Tags read: `:20:` reference, `:25:` account, `:28C:` statement number, `:60F:` opening balance,
 * `:61:` statement lines (each optionally followed by `:86:`), `:62F:` closing balance. One
 * statement per message: an intermediate balance (`:60M:` / `:62M:`, a multi-page statement) is
 * refused rather than half-read. The SWIFT envelope (`{1:..}{2:..}{4:` .. `-}`) may be present.
 *
 * Mapping to the camt.053 model:
 * - `statementId` = `:20:` + "/" + `:28C:` — `:20:` alone is reused by some correspondents.
 * - `iban` = `:25:` after its last "/" (the `BIC/account` form), spaces removed, upper-cased.
 * - opening / closing balance and dates from `:60F:` / `:62F:`; D is a debit balance (negative).
 * - a `:61:` line's booking date is its entry date (MMDD) when given, else its value date; its
 *   mark C / RD is money in (CRDT), D / RC money out (DBIT) — a reversal flips the direction.
 * - reference: the account-owner reference unless `NONREF`, else the servicing institution's
 *   reference after `//`, else the `:86:` narrative — the camt.053 order EndToEndId / AcctSvcrRef /
 *   unstructured remittance.
 */
object Mt940Parser {

    /** A statement body larger than this is refused before it is read: the camt limit's order. */
    const val MAX_BYTES = 1_048_576

    private const val MAX_REFERENCE = 255

    private val TAG = Regex("^:(\\d{2}[A-Z]?):(.*)$")
    private val BALANCE = Regex("^(?<mark>[CD])(?<date>\\d{6})(?<ccy>[A-Z]{3})(?<amount>\\d{1,15},\\d{0,4})$")
    private val LINE_61 =
        Regex(
            "^(?<value>\\d{6})(?<entry>\\d{4})?(?<mark>RC|RD|C|D)[A-Z]?(?<amount>\\d{1,15},\\d{0,4})" +
                "[NFS][A-Z0-9]{3}(?<owner>[^/]{0,16})(?://(?<bank>.{0,16}))?$",
        )

    private fun MatchResult.group(name: String): String = groups[name]?.value.orEmpty()

    fun parse(bytes: ByteArray): NostroStatement {
        require(bytes.size <= MAX_BYTES) { "MT940 statement is larger than $MAX_BYTES bytes" }
        val text = decode(bytes)
        val fields = fields(block4(text))
        val tags = fields.map { it.first }

        listOf("20", "25", "28C", "60F", "62F").forEach { t ->
            val n = tags.count { it == t }
            require(n == 1) { "MT940 must carry exactly one :$t: field, found $n" }
        }
        require("60M" !in tags && "62M" !in tags) {
            "multi-page MT940 (:60M:/:62M:) is not supported; upload one statement"
        }
        val unknown = tags.filterNot { it in KNOWN }.distinct()
        require(unknown.isEmpty()) { "MT940 carries unsupported field(s) ${unknown.joinToString { ":$it:" }}" }
        require(tags.indexOf("60F") < tags.indexOf("62F")) { "MT940 :60F: must precede :62F:" }

        val value = fields.associate { it.first to it.second }
        val reference = single(value.getValue("20"), "20")
        val statementNo = single(value.getValue("28C"), "28C")
        val opening = balance(single(value.getValue("60F"), "60F"), "60F")
        val closing = balance(single(value.getValue("62F"), "62F"), "62F")
        require(opening.currency == closing.currency) {
            "MT940 opening balance is in ${opening.currency}, closing in ${closing.currency}"
        }

        val entries = entries(fields, opening.currency)
        return NostroStatement(
            statementId = "$reference/$statementNo".also {
                require(it.length <= MAX_STATEMENT_ID) {
                    "MT940 :20:/:28C: is longer than $MAX_STATEMENT_ID characters"
                }
            },
            iban = iban(single(value.getValue("25"), "25")),
            currency = opening.currency,
            openingDate = opening.date,
            statementDate = closing.date,
            openingBalance = opening.amount,
            closingBalance = closing.amount,
            entries = entries,
        ).requireWithinPeriod()
    }

    private const val MAX_STATEMENT_ID = 64

    private val KNOWN = setOf("20", "21", "25", "28C", "60F", "60M", "61", "86", "62F", "62M", "64", "65")

    /** Strict UTF-8 (the SWIFT X character set is a subset); any control character but CR/LF is refused. */
    private fun decode(bytes: ByteArray): String {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("MT940 statement is not valid UTF-8", e)
        }
        require(text.none { it.isISOControl() && it != '\r' && it != '\n' }) {
            "MT940 statement carries a control character"
        }
        require(text.isNotBlank()) { "an MT940 body is required" }
        return text
    }

    /** The text block `{4:` .. `-}` when a SWIFT envelope is present, else the whole text. */
    private fun block4(text: String): String {
        val start = text.indexOf("{4:")
        if (start < 0) return text
        val end = text.indexOf("-}", start)
        require(end > start) { "MT940 block 4 is not terminated by '-}'" }
        return text.substring(start + "{4:".length, end)
    }

    /** (tag, value) in order; a line not starting with a tag continues the previous field. */
    private fun fields(body: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        body.split("\r\n", "\n").map { it.trimEnd('\r') }.forEach { line ->
            val m = TAG.matchEntire(line)
            when {
                m != null -> out += m.groupValues[1] to m.groupValues[2]
                line.isBlank() || line == "-" -> Unit
                else -> {
                    require(out.isNotEmpty()) { "MT940 text before the first field: '${line.take(MAX_ECHO)}'" }
                    val (tag, v) = out.removeAt(out.lastIndex)
                    out += tag to "$v\n$line"
                }
            }
        }
        require(out.isNotEmpty()) { "MT940 statement carries no fields" }
        return out
    }

    private const val MAX_ECHO = 40

    private fun single(v: String, tag: String): String {
        val s = v.trim()
        require(s.isNotEmpty() && '\n' !in s) { "MT940 :$tag: must be one non-empty line" }
        return s
    }

    private fun iban(raw: String): String {
        val iban = raw.substringAfterLast('/').replace(" ", "").uppercase()
        require(iban.matches(Regex("[A-Z]{2}\\d{2}[A-Z0-9]{1,30}"))) { "MT940 :25: does not end in an IBAN: '$raw'" }
        return iban
    }

    private data class Balance(val date: LocalDate, val currency: String, val amount: BigDecimal)

    private fun balance(raw: String, tag: String): Balance {
        val m =
            requireNotNull(BALANCE.matchEntire(raw)) { "MT940 :$tag: is not a balance (C/D YYMMDD CCY amount): '$raw'" }
        val value = Mt940Values.amount(m.group("amount"), tag)
        val signed = if (m.group("mark") == "D") value.negate() else value
        return Balance(Mt940Values.date(m.group("date"), tag), m.group("ccy"), signed)
    }

    private fun entries(fields: List<Pair<String, String>>, currency: String): List<StatementEntry> {
        val out = mutableListOf<StatementEntry>()
        fields.forEachIndexed { i, (tag, v) ->
            if (tag == "86") {
                require(i > 0 && fields[i - 1].first == "61") { "MT940 :86: must follow a :61: line" }
            }
            if (tag != "61") return@forEachIndexed
            val narrative = fields.getOrNull(i + 1)?.takeIf { it.first == "86" }?.second
            out += entry(v, narrative, out.size + 1, currency)
        }
        return out
    }

    private fun entry(raw: String, narrative: String?, sequence: Int, currency: String): StatementEntry {
        val firstLine = raw.lineSequence().first().trim()
        val m =
            requireNotNull(LINE_61.matchEntire(firstLine)) {
                "MT940 :61: line $sequence is malformed: '${firstLine.take(MAX_ECHO)}'"
            }
        val mark = m.group("mark")
        val valueDate = Mt940Values.date(m.group("value"), "61")
        val entryRaw = m.group("entry")
        val booking = if (entryRaw.isEmpty()) valueDate else Mt940Values.entryDate(valueDate, entryRaw)
        val amount = Mt940Values.amount(m.group("amount"), "61")
        require(amount.signum() > 0) { "MT940 :61: line $sequence has a zero amount" }
        val reference = listOf(
            m.group("owner").trim().takeUnless { it.isEmpty() || it == "NONREF" },
            m.group("bank").trim().takeUnless { it.isEmpty() },
            narrative?.replace('\n', ' ')?.trim()?.takeUnless { it.isEmpty() },
        ).firstOrNull { it != null }?.take(MAX_REFERENCE)
        return StatementEntry(
            sequence = sequence,
            amount = amount,
            currency = currency,
            direction = if (mark == "C" || mark == "RD") StatementDirection.CRDT else StatementDirection.DBIT,
            bookingDate = booking,
            reference = reference,
        )
    }
}

/** MT940 dates and amounts, held to what the store can represent — the camt.053 rules. */
internal object Mt940Values {
    private const val MAX_INTEGER_DIGITS = 15
    private val YYMMDD: DateTimeFormatter = DateTimeFormatter.ofPattern(
        "uuMMdd",
    ).withResolverStyle(ResolverStyle.STRICT)
    private val MMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("MMdd")

    /** YYMMDD in 2000–2099 (the `uu` pattern's base), strictly: 260230 is refused, not rolled over. */
    fun date(yymmdd: String, tag: String): LocalDate = try {
        LocalDate.parse(yymmdd, YYMMDD)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("MT940 :$tag: carries an invalid date '$yymmdd'", e)
    }

    /** The entry date carries no year: the one nearest the value date (a line can cross New Year). */
    fun entryDate(valueDate: LocalDate, mmdd: String): LocalDate {
        val md = try {
            MonthDay.parse(mmdd, MMDD)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("MT940 :61: entry date '$mmdd' is not a date", e)
        }
        return listOf(-1, 0, 1).mapNotNull { dy ->
            val y = valueDate.year + dy
            if (md.isValidYear(y)) md.atYear(y) else null
        }.minBy { abs(it.toEpochDay() - valueDate.toEpochDay()) }
    }

    /** SWIFT decimal comma; held to what NUMERIC(19,4) stores exactly, like camt.053. */
    fun amount(raw: String, tag: String): BigDecimal {
        val normalized = raw.replace(',', '.').removeSuffix(".")
        val value = BigDecimal(normalized)
        require(value.precision() - value.scale() <= MAX_INTEGER_DIGITS) {
            "MT940 :$tag: amount has more than $MAX_INTEGER_DIGITS integer digits: $raw"
        }
        return value
    }
}
