// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import com.openbank.libs.domain.account.CzechAccountNumber

/**
 * Is a stored CZ IBAN one a Czech bank could actually have issued? `CZkk BBBB PPPPPP NNNNNNNNNN`:
 * the 6-digit prefix (positions 9–14) and the 10-digit base (positions 15–24) must each satisfy the
 * ČNB mod-11 weighted checksum ([CzechAccountNumber]). mod-97 is NOT checked here — `Iban.of` already
 * refuses a bad one, so every stored value passes it; the defect this finds is a valid mod-97 over a
 * BBAN no bank issues.
 */
object CzechIbanValidity {
    private const val CZ_IBAN_LENGTH = 24
    private const val PREFIX_START = 8
    private const val BASE_START = 14

    /** True for a non-CZ value (out of scope) and for a nationally valid CZ one. */
    fun isNationallyValid(iban: String): Boolean {
        if (!iban.startsWith("CZ")) return true
        if (iban.length != CZ_IBAN_LENGTH || !iban.substring(2).all { it.isDigit() }) return false
        return CzechAccountNumber.isValid(
            prefix = iban.substring(PREFIX_START, BASE_START),
            base = iban.substring(BASE_START, CZ_IBAN_LENGTH),
        )
    }
}
