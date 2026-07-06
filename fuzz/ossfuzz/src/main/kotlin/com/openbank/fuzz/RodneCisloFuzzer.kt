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
 */
object RodneCisloFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val raw = data.consumeRemainingAsString()
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
    }
}
