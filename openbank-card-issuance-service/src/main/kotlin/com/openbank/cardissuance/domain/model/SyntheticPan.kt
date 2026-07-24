// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

import java.security.SecureRandom

/** A freshly generated synthetic card credential. Never persisted or logged in the clear. */
data class SyntheticCardCredential(val pan: String, val cvv: String, val maskedPan: String)

/**
 * Generates **synthetic, Luhn-valid test PANs** for this sandbox bank.
 *
 * OpenBank is a reference implementation: it issues no real cards and holds no real cardholder
 * data. Every number produced here is drawn from a publicly documented *test* BIN — the same
 * prefixes every payment SDK ships in its docs — so a generated PAN can never collide with a
 * live account range at any issuer. The value is still treated as a secret at rest (AES-256-GCM,
 * see `CardSecretCipher`) because the code path must be indistinguishable from a real one: a
 * sandbox that stores its PANs in the clear teaches the wrong shape.
 *
 * Correctness note: before this existed, `CardService` set `maskedPan` to
 * `"**** **** **** ${(1000..9999).random()}"` — four digits that corresponded to no number at
 * all. Every masked PAN the platform displayed was a lie, and a customer comparing the app's
 * last-4 against anything else would never match. [generate] now derives the mask FROM the PAN it
 * just produced, so the last 4 are real.
 */
object SyntheticPanGenerator {

    /**
     * ISO/IEC 7812 major-industry-identifier prefixes, one per network. These are the
     * **well-known test BINs** (Visa 411111, Mastercard 555555, Amex 378282, UnionPay 621234) —
     * they are documented sandbox ranges, not issuer-assigned production BINs, and the numbers
     * built on top of them are synthetic. This table is the single place the prefixes live.
     */
    private val TEST_BIN_PREFIXES = mapOf(
        CardNetwork.VISA to "411111",
        CardNetwork.MASTERCARD to "555555",
        CardNetwork.AMEX to "378282",
        CardNetwork.UNIONPAY to "621234",
    )

    /** Amex is 15 digits with a 4-digit CID; every other supported network is 16 with a 3-digit CVV. */
    private const val PAN_LENGTH_DEFAULT = 16
    private const val PAN_LENGTH_AMEX = 15
    private const val CVV_LENGTH_DEFAULT = 3
    private const val CVV_LENGTH_AMEX = 4
    private const val MASK_VISIBLE_DIGITS = 4
    private const val DECIMAL_RADIX = 10
    private const val LUHN_DOUBLE_ROLLOVER = 9

    private val random = SecureRandom()

    fun generate(network: CardNetwork): SyntheticCardCredential {
        val prefix = TEST_BIN_PREFIXES.getValue(network)
        val panLength = if (network == CardNetwork.AMEX) PAN_LENGTH_AMEX else PAN_LENGTH_DEFAULT
        val cvvLength = if (network == CardNetwork.AMEX) CVV_LENGTH_AMEX else CVV_LENGTH_DEFAULT

        val body = buildString {
            append(prefix)
            repeat(panLength - prefix.length - 1) { append(random.nextInt(DECIMAL_RADIX)) }
        }
        val pan = body + luhnCheckDigit(body)
        val cvv = (1..cvvLength).joinToString("") { random.nextInt(DECIMAL_RADIX).toString() }
        return SyntheticCardCredential(pan = pan, cvv = cvv, maskedPan = mask(pan))
    }

    /** `**** **** **** 1234` — the last [MASK_VISIBLE_DIGITS] of the real PAN, everything else hidden. */
    fun mask(pan: String): String = "**** **** **** ${pan.takeLast(MASK_VISIBLE_DIGITS)}"

    /** True when [pan] satisfies the Luhn (mod-10) checksum. */
    fun isLuhnValid(pan: String): Boolean {
        if (pan.isEmpty() || !pan.all { it.isDigit() }) return false
        return (luhnSum(pan.dropLast(1)) + pan.last().digitToInt()) % DECIMAL_RADIX == 0
    }

    private fun luhnCheckDigit(body: String): Int = (DECIMAL_RADIX - luhnSum(body) % DECIMAL_RADIX) % DECIMAL_RADIX

    /** Luhn sum over [body], which is the PAN *without* its check digit (so doubling starts at the end). */
    private fun luhnSum(body: String): Int = body.reversed()
        .mapIndexed { index, char ->
            val digit = char.digitToInt()
            if (index % 2 == 0) {
                val doubled = digit * 2
                if (doubled > LUHN_DOUBLE_ROLLOVER) doubled - LUHN_DOUBLE_ROLLOVER else doubled
            } else {
                digit
            }
        }
        .sum()
}
