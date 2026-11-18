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

    /** The documented narrowings; [n] is already known to be stdnum-valid digits. */
    private fun stricterRuleRejects(n: String): Boolean {
        val yy = n.substring(0, 2).toInt()
        val mm = n.substring(2, 4).toInt()
        val overflow = mm in 21..32 || mm in 71..82
        return when {
            n.length == 9 && yy > 53 -> true
            mm !in ENCODED_MONTHS -> true
            n.length == 9 && overflow -> true
            overflow && yy < 4 -> true
            n.length == 10 && n.substring(0, 9).toLong() % 11 == 10L -> {
                val year = if (overflow || yy <= 53) 2000 + yy else 1900 + yy
                year > 1985
            }
            else -> false
        }
    }
}
