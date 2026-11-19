// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.fuzz

import java.time.DateTimeException
import java.time.LocalDate

/**
 * Independent reference verdict for a Czech birth number, used as the fuzzer's semantic oracle
 * (#16748). Deliberately NOT derived from [com.openbank.libs.identity.RodneCislo]: it is a
 * transliteration of python-stdnum's `stdnum.cz.rc` (2.2) followed by the documented rules where
 * the shared validator is stricter — the same narrowing `.github/scripts/identifier-differential.py`
 * applies. Narrowing only ever turns "valid" into "invalid", so a wrong-accept by the parser
 * always disagrees with this reference.
 */
internal object RodneCisloReference {

    fun isValid(raw: String): Boolean {
        val n = raw.trim().replace("/", "").replace(" ", "")
        return stdnumAccepts(n) && !stricterRuleRejects(n)
    }

    /** stdnum.cz.rc.validate, rule for rule. */
    private fun stdnumAccepts(n: String): Boolean {
        if (n.isEmpty() || !n.all { it in '0'..'9' } || n.length !in setOf(9, 10)) return false
        var year = 1900 + n.substring(0, 2).toInt()
        val month = n.substring(2, 4).toInt() % 50 % 20
        val day = n.substring(4, 6).toInt()
        if (n.length == 9) {
            if (year >= 1980) year -= 100
            if (year > 1953) return false
        } else if (year < 1954) {
            year += 100
        }
        try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            return false
        }
        if (n.length == 10) {
            val check = (n.substring(0, 9).toLong() % 11).let { if (it == 10L) 0L else it }
            if (n.last().digitToInt().toLong() != check) return false
        }
        return true
    }

    private val ENCODED_MONTHS = (1..12).flatMap { listOf(it, it + 20, it + 50, it + 70) }.toSet()

    /**
     * The documented narrowings; [n] is already known to be stdnum-valid digits. Sources, per rule:
     * statute is zákon č. 133/2000 Sb. § 13 (https://www.zakonyprolidi.cz/cs/2000-133); structure and
     * history are described by MV ČR (https://www.mvcr.cz/clanek/rodne-cislo.aspx). Rules marked
     * "practice" are established issuing practice, for which no paragraph is claimed.
     * This model shares its author with the Kotlin parser, so the citations are what make it checkable.
     */
    private fun stricterRuleRejects(n: String): Boolean {
        val yy = n.substring(0, 2).toInt()
        val mm = n.substring(2, 4).toInt()
        val overflow = mm in 21..32 || mm in 71..82
        return when {
            // practice: the 9-digit form was issued only up to 1953 (MV ČR)
            n.length == 9 && yy > 53 -> true
            // month field is M, M+20, M+50 or M+70 (§ 13; MV ČR)
            mm !in ENCODED_MONTHS -> true
            // practice: the overflow encodings belong to the 10-digit form from 2004 (MV ČR)
            n.length == 9 && overflow -> true
            // practice: +20/+70 exist only from 2004 (MV ČR)
            overflow && yy < 4 -> true
            // practice: remainder 10 / check digit 0 was issued only up to 1985 (MV ČR)
            n.length == 10 && n.substring(0, 9).toLong() % 11 == 10L -> {
                val year = if (overflow || yy <= 53) 2000 + yy else 1900 + yy
                year > 1985
            }
            else -> false
        }
    }
}
