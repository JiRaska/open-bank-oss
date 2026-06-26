// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.domain.account

data class Iban(val value: String) {
    init {
        require(isValid(value)) { "Invalid IBAN: $value" }
    }

    val countryCode: String get() = value.substring(0, 2)
    val checkDigits: String get() = value.substring(2, 4)
    val bban: String get() = value.substring(4)

    fun formatted(): String = value.chunked(4).joinToString(" ")

    override fun toString(): String = value

    companion object {
        fun of(raw: String): Iban = Iban(raw.replace(" ", "").uppercase())

        fun isValid(iban: String): Boolean {
            val cleaned = iban.replace(" ", "").uppercase()
            if (cleaned.length < 15 || cleaned.length > 34) return false
            if (!cleaned.substring(0, 2).all { it.isLetter() }) return false
            if (!cleaned.substring(2, 4).all { it.isDigit() }) return false
            val rearranged = cleaned.substring(4) + cleaned.substring(0, 4)
            val numeric = rearranged.map { c ->
                if (c.isDigit()) c.toString() else (c.code - 'A'.code + 10).toString()
            }.joinToString("")
            return numeric.toBigInteger().mod(97.toBigInteger()).toInt() == 1
        }
    }
}
