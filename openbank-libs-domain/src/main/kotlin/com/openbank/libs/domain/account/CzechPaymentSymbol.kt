// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.domain.account

/**
 * Czech domestic payment symbols. Each kind has its own maximum length, and the CONSTANT symbol is
 * the short one: the domestic-payment system of record stores it as `VARCHAR(4)` with
 * `CHECK ^[0-9]{1,4}$` (ČNB KS 0-9999). A shared 10-digit rule would let a 5-digit KS through every
 * edge and fail only at the downstream flush, so the length lives here, per kind, once.
 * Leading zeros are insignificant but allowed.
 */
enum class CzechPaymentSymbol(val fieldName: String, val maxLength: Int) {
    VARIABLE("variableSymbol", Limits.LONG),
    SPECIFIC("specificSymbol", Limits.LONG),
    CONSTANT("constantSymbol", Limits.CONSTANT),
    ;

    private object Limits {
        const val LONG = 10
        const val CONSTANT = 4
    }

    /** True iff [value] is 1..[maxLength] ASCII digits. Blank/null is "no symbol" and is the caller's call. */
    fun isValid(value: String): Boolean = value.length in 1..maxLength && value.all { it in '0'..'9' }

    /**
     * [value] trimmed, or null when it is null or blank (an unused field, as clients send it);
     * otherwise [IllegalArgumentException] naming the field and its limit.
     */
    fun normalise(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(isValid(trimmed)) { "$fieldName must be 1-$maxLength digits" }
        return trimmed
    }
}
