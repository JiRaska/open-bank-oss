// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

/**
 * Closed value types for caller-supplied values the edge splices into an upstream URL.
 *
 * Each one is parsed once at the resource boundary and rebuilt from the edge's OWN alphabet
 * constants, so the value that reaches the URL carries no caller-supplied character: it is the
 * canonical constant form, URL-safe by construction, never the request string itself (same
 * pattern as `PensionStrategyCode`). Whether the value names something real — an open pocket, a
 * saved payee — stays the upstream's answer; the edge only owns the shape.
 */
private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val LETTERS = UPPER + "abcdefghijklmnopqrstuvwxyz"
private const val ALPHANUMERIC = LETTERS + "0123456789"

/** [raw] rebuilt character by character from [alphabet], or null when any character is off-alphabet. */
private fun canonical(raw: String?, alphabet: String, lengths: IntRange): String? {
    if (raw == null || raw.length !in lengths) return null
    val out = CharArray(raw.length)
    for (i in raw.indices) {
        val index = alphabet.indexOf(raw[i])
        if (index < 0) return null
        out[i] = alphabet[index]
    }
    return String(out)
}

/** ISO 4217 alpha code shape: exactly three upper-case letters. Existence is the upstream's answer. */
@JvmInline
value class CurrencyCode private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private const val LENGTH = 3

        /** The canonical code, or null when [raw] is not three upper-case ASCII letters. */
        fun parse(raw: String?): CurrencyCode? = canonical(raw, UPPER, LENGTH..LENGTH)?.let(::CurrencyCode)
    }
}

/** ISO 3166-1 alpha-2 shape: two ASCII letters, case preserved as the caller sent it. */
@JvmInline
value class CountryCode private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private const val LENGTH = 2

        /** The canonical code, or null when [raw] is not two ASCII letters. */
        fun parse(raw: String?): CountryCode? = canonical(raw, LETTERS, LENGTH..LENGTH)?.let(::CountryCode)
    }
}

/**
 * A saved payee's IBAN as a path segment: 1–34 ASCII letters or digits (34 is the ISO 13616
 * maximum). Deliberately no checksum — removing a payee is idempotent and a stored IBAN that fails
 * mod-97 must still be removable, so the edge owns only the alphabet, never validity.
 */
@JvmInline
value class PayeeIban private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private const val MAX_LENGTH = 34

        /** The canonical IBAN, or null when [raw] is empty, too long or carries a non-alphanumeric character. */
        fun parse(raw: String?): PayeeIban? = canonical(raw, ALPHANUMERIC, 1..MAX_LENGTH)?.let(::PayeeIban)
    }
}
