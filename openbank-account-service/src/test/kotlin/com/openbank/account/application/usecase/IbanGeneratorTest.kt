// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.libs.domain.account.CzechAccountNumber
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The generator must emit IBANs that hold under BOTH checks simultaneously:
 *  - ISO 13616 mod-97-10 (generic IBAN check digits), and
 *  - the Czech national mod-11 weighting on the prefix and base inside the BBAN.
 *
 * The previous implementation only satisfied the first — it shipped a `System.nanoTime()`
 * tail under bank code `0000`, an account no Czech bank could issue. These tests pin the
 * regression: a generated value is decomposed back into bank/prefix/base and each part is
 * re-validated independently.
 */
class IbanGeneratorTest {

    private val bankCode = "0800"
    private val generator = IbanGenerator(bankCode)

    @Test
    fun `generated IBAN passes ISO 13616 mod-97`() {
        repeat(500) {
            val iban = generator.generate(CurrencyCode.CZK)
            assertThat(Iban.isValid(iban.value))
                .withFailMessage("generated IBAN %s fails ISO mod-97", iban.value)
                .isTrue()
        }
    }

    @Test
    fun `generated IBAN is a 24-char Czech IBAN with the configured bank code`() {
        val iban = generator.generate(CurrencyCode.CZK)
        assertThat(iban.value).hasSize(24) // CZ + 2 check + 20 BBAN
        assertThat(iban.countryCode).isEqualTo("CZ")
        assertThat(iban.bban).startsWith(bankCode)
    }

    @Test
    fun `generated BBAN base satisfies the Czech national mod-11 checksum`() {
        repeat(500) {
            val iban = generator.generate(CurrencyCode.CZK)
            val bban = iban.bban // bankCode(4) + prefix(6) + base(10)
            val prefix = bban.substring(4, 10)
            val base = bban.substring(10, 20)
            assertThat(CzechAccountNumber.isValidPrefix(prefix))
                .withFailMessage("prefix %s of %s fails mod-11", prefix, iban.value)
                .isTrue()
            assertThat(CzechAccountNumber.isValidBase(base))
                .withFailMessage("base %s of %s fails mod-11", base, iban.value)
                .isTrue()
        }
    }

    @Test
    fun `successive IBANs differ (not a constant)`() {
        val sample = (1..50).map { generator.generate(CurrencyCode.CZK).value }.toSet()
        assertThat(sample.size).isGreaterThan(1)
    }

    @Test
    fun `default sandbox bank code 0000 still yields valid Czech IBANs`() {
        // 0000 is the reserved sandbox placeholder (not a real ČNB-assigned code, unlike 2010 = Fio).
        // The bank code is outside the mod-11 weighting, so an unassigned code is still BBAN-valid.
        val sandbox = IbanGenerator("0000")
        repeat(200) {
            val iban = sandbox.generate(CurrencyCode.CZK)
            assertThat(Iban.isValid(iban.value))
                .withFailMessage("IBAN %s under bank code 0000 fails ISO mod-97", iban.value)
                .isTrue()
            assertThat(iban.bban).startsWith("0000")
            assertThat(CzechAccountNumber.isValidBase(iban.bban.substring(10, 20)))
                .withFailMessage("base of %s fails mod-11", iban.value)
                .isTrue()
        }
    }
}
