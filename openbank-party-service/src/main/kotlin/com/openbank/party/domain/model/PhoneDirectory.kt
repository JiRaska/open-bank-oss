// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import java.security.MessageDigest

/**
 * Phone-number matching for pay-to-phone.
 *
 * **What the hash is for, and what it is not.** Hashing keeps plaintext phone numbers out of
 * request bodies, access logs and this service's indexes — that is a real and worthwhile
 * reduction in where a number can leak from. It is NOT a privacy guarantee against OpenBank:
 * the phone-number space is small enough to enumerate, and this service holds both the numbers
 * and the hashes anyway. Anyone reading `phone_hash` should read it as "not stored in the clear",
 * never as "we cannot tell whose number this is".
 *
 * The protections that actually bound this feature are elsewhere: a party is only matchable
 * after opting in ([Party.discoverable]), a lookup only ever answers about numbers the caller
 * already had, and non-matching hashes are never persisted.
 */
object PhoneDirectory {

    private const val CZ_COUNTRY_CODE = "+420"
    private const val CZ_NATIONAL_DIGITS = 9
    private const val MIN_E164_DIGITS = 8
    private const val MAX_E164_DIGITS = 15

    /**
     * Canonical E.164 for [raw], or null when it cannot be trusted.
     *
     * A bare 9-digit national number is assumed Czech — the only country this bank operates in.
     * Anything that does not land on a plausible E.164 returns null rather than a guess: an
     * unmatched number costs a convenience, a WRONGLY matched one addresses a payment to a
     * stranger.
     */
    fun normalise(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return when {
            !trimmed.startsWith("+") && digits.length == CZ_NATIONAL_DIGITS && !digits.startsWith("0") ->
                "$CZ_COUNTRY_CODE$digits"
            trimmed.startsWith("+") && digits.length in MIN_E164_DIGITS..MAX_E164_DIGITS -> "+$digits"
            // "00420…" — the other way people write an international prefix.
            digits.startsWith("00") && digits.length - 2 in MIN_E164_DIGITS..MAX_E164_DIGITS ->
                "+${digits.removePrefix("00")}"
            else -> null
        }
    }

    /** Lowercase hex SHA-256 of the E.164 form of [raw]; null when [raw] does not normalise. */
    fun hash(raw: String?): String? {
        val e164 = normalise(raw) ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(e164.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
