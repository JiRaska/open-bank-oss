// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.domain.account

import java.security.SecureRandom

/**
 * Czech national bank-account number rules (ČNB Decree 169/2011 Sb., § 5).
 *
 * A Czech account number is `prefix-base/bankcode`, where BOTH the (optional, ≤6 digit) prefix
 * and the (≤10 digit) base must each satisfy a modulo-11 weighted-digit checksum. This is a
 * *national* rule that sits **inside** the BBAN and is entirely independent of the ISO 13616
 * IBAN mod-97 check digits — an IBAN can be mod-97-valid yet carry a BBAN that no Czech bank
 * would ever issue. Account generation MUST satisfy both, or the resulting "account" does not
 * exist in the national scheme (the bug this type fixes: the old generator emitted a padded
 * `System.nanoTime()` tail with no mod-11 guarantee).
 *
 * Weights are applied right-aligned (units digit first). Per the decree:
 *   prefix (6 positions, left→right): 10 5 8 4 2 1
 *   base  (10 positions, left→right):  6 3 7 9 10 5 8 4 2 1
 * The weighted sum of each part must be ≡ 0 (mod 11).
 */
object CzechAccountNumber {

    /** Left→right positional weights for the ≤6-digit prefix. */
    private val PREFIX_WEIGHTS = intArrayOf(10, 5, 8, 4, 2, 1)

    /** Left→right positional weights for the ≤10-digit base number. */
    private val BASE_WEIGHTS = intArrayOf(6, 3, 7, 9, 10, 5, 8, 4, 2, 1)

    private const val PREFIX_LEN = 6
    private const val BASE_LEN = 10

    private val rng = SecureRandom()

    /** Weighted mod-11 over [digits], left-padded with zeros to [weights].size positions. */
    private fun weightedMod11(digits: String, weights: IntArray): Int {
        require(digits.length <= weights.size) { "too many digits for the weight vector" }
        require(digits.all { it.isDigit() }) { "non-digit in account number part: $digits" }
        val padded = digits.padStart(weights.size, '0')
        var sum = 0
        for (i in weights.indices) sum += (padded[i] - '0') * weights[i]
        return sum % 11
    }

    /** A ≤6-digit prefix is valid when its weighted digit sum is divisible by 11. */
    fun isValidPrefix(prefix: String): Boolean = prefix.length in 0..PREFIX_LEN &&
        prefix.all { it.isDigit() } &&
        weightedMod11(prefix, PREFIX_WEIGHTS) == 0

    /**
     * A ≤10-digit base number is valid when its weighted digit sum is divisible by 11 and it
     * carries at least two non-zero digits (ČNB minimum significance — rejects `0000000000`).
     */
    fun isValidBase(base: String): Boolean = base.length in 2..BASE_LEN &&
        base.all { it.isDigit() } &&
        base.count { it != '0' } >= 2 &&
        weightedMod11(base, BASE_WEIGHTS) == 0

    /** True when both parts independently satisfy the national mod-11 checksum. */
    fun isValid(prefix: String, base: String): Boolean = isValidPrefix(prefix) && isValidBase(base)

    /**
     * Generate a random 10-digit base number that satisfies the mod-11 checksum. We draw the
     * first nine digits, then solve the tenth (its weight is 1) so the weighted sum ≡ 0 (mod 11);
     * when the solution would require a non-existent "digit" 10 we re-draw. Guaranteed-valid by
     * construction — verified by [isValidBase].
     */
    fun generateBase(): String {
        while (true) {
            val head = IntArray(9) { rng.nextInt(10) }
            if (head.count { it != 0 } < 2) continue // keep ≥2 significant digits
            var sum = 0
            for (i in head.indices) sum += head[i] * BASE_WEIGHTS[i]
            val last = (11 - (sum % 11)) % 11 // BASE_WEIGHTS[9] == 1, so this closes the checksum
            if (last == 10) continue
            val base = head.joinToString("") + last
            if (isValidBase(base)) return base
        }
    }

    /**
     * Compose a full 20-digit Czech BBAN `bankCode(4) + prefix(6) + base(10)`. The [bankCode] is
     * the ČNB-assigned 4-digit bank identifier; [prefix] defaults to the (valid) empty prefix.
     */
    fun composeBban(bankCode: String, base: String, prefix: String = ""): String {
        require(bankCode.length == 4 && bankCode.all { it.isDigit() }) { "bankCode must be 4 digits" }
        require(isValidPrefix(prefix)) { "invalid Czech prefix: $prefix" }
        require(isValidBase(base)) { "invalid Czech base number: $base" }
        return bankCode + prefix.padStart(PREFIX_LEN, '0') + base.padStart(BASE_LEN, '0')
    }
}
