// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.account

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * National mod-11 rules per ČNB Decree 169/2011 Sb. Vectors are real Czech account parts:
 * prefix `19` and base `2000145399` are the published example `19-2000145399/0800`
 * (canonical IBAN `CZ65 0800 0000 1920 0014 5399`).
 */
class CzechAccountNumberTest {

    @Test
    fun `accepts known-valid prefixes (mod-11 == 0)`() {
        assertThat(CzechAccountNumber.isValidPrefix("")).isTrue() // empty prefix trivially valid
        assertThat(CzechAccountNumber.isValidPrefix("19")).isTrue()
        assertThat(CzechAccountNumber.isValidPrefix("000019")).isTrue()
    }

    @Test
    fun `rejects prefixes whose weighted sum is not divisible by 11`() {
        assertThat(CzechAccountNumber.isValidPrefix("100000")).isFalse() // mod-11 == 10
        assertThat(CzechAccountNumber.isValidPrefix("18")).isFalse()
    }

    @Test
    fun `rejects non-digit or over-length prefixes`() {
        assertThat(CzechAccountNumber.isValidPrefix("12a")).isFalse()
        assertThat(CzechAccountNumber.isValidPrefix("1234567")).isFalse() // > 6 digits
    }

    @Test
    fun `accepts known-valid bases (mod-11 == 0, at least 2 significant digits)`() {
        assertThat(CzechAccountNumber.isValidBase("19")).isTrue()
        assertThat(CzechAccountNumber.isValidBase("2000145399")).isTrue()
        assertThat(CzechAccountNumber.isValidBase("1234567899")).isTrue()
    }

    @Test
    fun `rejects bases whose weighted sum is not divisible by 11`() {
        assertThat(CzechAccountNumber.isValidBase("145399")).isFalse() // mod-11 == 10
        assertThat(CzechAccountNumber.isValidBase("1234567890")).isFalse() // mod-11 == 2
    }

    @Test
    fun `rejects bases with fewer than two significant digits`() {
        assertThat(CzechAccountNumber.isValidBase("0000000000")).isFalse()
        assertThat(CzechAccountNumber.isValidBase("10")).isFalse() // mod-11 != 0 anyway, only 1 nonzero
        assertThat(CzechAccountNumber.isValidBase("0")).isFalse() // too short + single digit
    }

    @Test
    fun `rejects non-digit or over-length bases`() {
        assertThat(CzechAccountNumber.isValidBase("12345678x9")).isFalse()
        assertThat(CzechAccountNumber.isValidBase("12345678901")).isFalse() // > 10 digits
    }

    @Test
    fun `isValid requires both parts to satisfy mod-11`() {
        assertThat(CzechAccountNumber.isValid(prefix = "19", base = "2000145399")).isTrue()
        assertThat(CzechAccountNumber.isValid(prefix = "18", base = "2000145399")).isFalse()
        assertThat(CzechAccountNumber.isValid(prefix = "19", base = "1234567890")).isFalse()
    }

    @Test
    fun `generateBase always yields a base that passes isValidBase`() {
        repeat(2_000) {
            val base = CzechAccountNumber.generateBase()
            assertThat(base.length).isEqualTo(10)
            assertThat(CzechAccountNumber.isValidBase(base))
                .withFailMessage("generated base %s is not a valid Czech base", base)
                .isTrue()
        }
    }

    @Test
    fun `generateBase is not constant across calls`() {
        val sample = (1..50).map { CzechAccountNumber.generateBase() }.toSet()
        assertThat(sample.size).isGreaterThan(1)
    }

    @Test
    fun `composeBban produces the canonical 20-digit BBAN for the published example`() {
        val bban = CzechAccountNumber.composeBban(bankCode = "0800", base = "2000145399", prefix = "19")
        assertThat(bban).isEqualTo("08000000192000145399")
        assertThat(bban.length).isEqualTo(20)
    }

    @Test
    fun `composeBban left-pads prefix and base into their fixed-width fields`() {
        val bban = CzechAccountNumber.composeBban(bankCode = "2010", base = "19")
        assertThat(bban).isEqualTo("20100000000000000019") // bank(4)+prefix(6 zeros)+base(10)
    }

    @Test
    fun `composeBban rejects a bank code that is not exactly four digits`() {
        assertThatThrownBy { CzechAccountNumber.composeBban(bankCode = "080", base = "2000145399") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CzechAccountNumber.composeBban(bankCode = "08x0", base = "2000145399") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `composeBban rejects parts that fail the national checksum`() {
        assertThatThrownBy { CzechAccountNumber.composeBban(bankCode = "0800", base = "1234567890") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CzechAccountNumber.composeBban(bankCode = "0800", base = "2000145399", prefix = "18") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
