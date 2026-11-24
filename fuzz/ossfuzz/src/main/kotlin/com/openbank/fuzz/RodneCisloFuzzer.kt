// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.openbank.libs.identity.RodneCislo

/**
 * Jazzer fuzz target for the Czech birth-number parser (KYC identity path).
 *
 * Properties:
 *  1. parse() is total: any input yields Parsed or Invalid, never a raw throwable
 *     (DateTimeException from the derived birthdate is the classic leak).
 *  2. parse/isValid agree: isValid(x) == (parse(x) is Parsed).
 *  3. Canonicalization is idempotent: parsing the canonical form of a Parsed result
 *     yields the same canonical form.
 *  4. SEMANTIC: the verdict equals [RodneCisloReference]'s (#16748). Properties 1-3 only check
 *     the parser against itself, so 68.8M inputs could never reveal a wrong-ACCEPT of a
 *     never-issued number; this one compares against an independent model of the spec.
 *     Raw byte strings almost never reach a valid birth number, so half the inputs are
 *     synthesised as structured candidates (date-shaped head, mostly-correct check digit).
 */
object RodneCisloFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val raw = if (data.consumeBoolean()) structured(data) else data.consumeRemainingAsString()
        val result = RodneCislo.parse(raw)
        val valid = RodneCislo.isValid(raw)
        check(valid == (result is RodneCislo.Parsed)) {
            "parse/isValid disagree for input: $raw"
        }
        if (result is RodneCislo.Parsed) {
            val reparsed = RodneCislo.parse(result.canonical)
            check(reparsed is RodneCislo.Parsed && reparsed.canonical == result.canonical) {
                "canonicalization not idempotent for input: $raw"
            }
        }
        check(valid == RodneCisloReference.isValid(raw)) {
            "parser says valid=$valid, spec reference says ${!valid} for input: $raw"
        }
    }

    private fun structured(data: FuzzedDataProvider): String {
        val yy = data.consumeInt(0, 99)
        val mm = data.consumeInt(0, 99)
        val dd = data.consumeInt(0, 32)
        val serial = data.consumeInt(0, 999)
        val head = "%02d%02d%02d%03d".format(yy, mm, dd, serial)
        if (data.consumeBoolean()) return head
        val rem = (head.toLong() % 11).toInt()
        val check = if (data.consumeBoolean()) (if (rem == 10) 0 else rem) else data.consumeInt(0, 9)
        return head + check
    }
}
