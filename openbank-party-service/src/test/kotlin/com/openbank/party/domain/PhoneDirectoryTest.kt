// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain

import com.openbank.party.domain.model.PhoneDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The normaliser decides who a pay-to-phone payment can be addressed to, so its failure mode
 * matters more than its hit rate: an unrecognised number costs a convenience, a number
 * normalised to the WRONG canonical form addresses money at a stranger.
 */
class PhoneDirectoryTest {

    /**
     * The same literal openbank-app's `PhoneNumbersTest` pins for this number.
     *
     * The two sides hash INDEPENDENTLY and compare the results, so agreeing on "some 64-hex
     * string" is not agreement: a change to normalisation or to the encoding on either side leaves
     * both suites green and surfaces to a customer as "your contact isn't on OpenBank" for someone
     * who is. Pinning the same value on both sides is what turns a one-sided change into a failing
     * test — before this, only the app pinned it, so a change HERE was the one direction nothing
     * could catch.
     *
     * It is sha256("+420601123456"). Recompute it if it ever fails; copying a new value out of the
     * failure message would only re-pin the drift.
     */
    private val pinned = "f417a9109171fe54f0433ef3af126615f06e2dacb615f11ffb59a4f7cdfd4c10"

    @Test
    fun `the same number written four ways hashes identically`() {
        val forms = listOf("+420 601 123 456", "+420601123456", "601 123 456", "00420601123456")
        val hashes = forms.map { PhoneDirectory.hash(it) }.toSet()
        assertThat(hashes).hasSize(1)
        assertThat(hashes.single()).isEqualTo(pinned)
    }

    @Test
    fun `a bare nine-digit number is treated as Czech`() {
        assertThat(PhoneDirectory.normalise("601123456")).isEqualTo("+420601123456")
    }

    @Test
    fun `an explicit country code is never overwritten with the Czech one`() {
        assertThat(PhoneDirectory.normalise("+44 20 7946 0958")).isEqualTo("+442079460958")
    }

    @Test
    fun `numbers that cannot be trusted normalise to null rather than a guess`() {
        // Too short, alphabetic, empty, a national number starting with a trunk prefix rather
        // than a subscriber digit, and an over-long E.164.
        val rejected = listOf(null, "", "   ", "12345", "0601123456", "not a phone", "+1234567890123456")
        assertThat(rejected.map { PhoneDirectory.normalise(it) }).containsOnlyNulls()
    }

    @Test
    fun `a hash is lowercase hex sha-256`() {
        assertThat(PhoneDirectory.hash("+420601123456")).isEqualTo(pinned)
    }

    @Test
    fun `different numbers do not collide`() {
        assertThat(PhoneDirectory.hash("+420601123456")).isNotEqualTo(PhoneDirectory.hash("+420601123457"))
    }
}
