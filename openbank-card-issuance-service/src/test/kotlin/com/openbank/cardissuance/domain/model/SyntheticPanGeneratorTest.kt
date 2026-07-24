// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SyntheticPanGeneratorTest {

    @Test fun `every generated PAN is Luhn valid`() {
        CardNetwork.entries.forEach { network ->
            repeat(REPEATS) {
                val credential = SyntheticPanGenerator.generate(network)
                assertThat(SyntheticPanGenerator.isLuhnValid(credential.pan))
                    .describedAs("%s PAN %s must satisfy Luhn", network, credential.maskedPan)
                    .isTrue()
            }
        }
    }

    @Test fun `PAN length is 15 for Amex and 16 elsewhere`() {
        assertThat(SyntheticPanGenerator.generate(CardNetwork.AMEX).pan).hasSize(15)
        assertThat(SyntheticPanGenerator.generate(CardNetwork.VISA).pan).hasSize(16)
        assertThat(SyntheticPanGenerator.generate(CardNetwork.MASTERCARD).pan).hasSize(16)
        assertThat(SyntheticPanGenerator.generate(CardNetwork.UNIONPAY).pan).hasSize(16)
    }

    @Test fun `CVV is 4 digits for Amex and 3 elsewhere`() {
        assertThat(SyntheticPanGenerator.generate(CardNetwork.AMEX).cvv).hasSize(4).containsOnlyDigits()
        assertThat(SyntheticPanGenerator.generate(CardNetwork.VISA).cvv).hasSize(3).containsOnlyDigits()
    }

    @Test fun `each network uses its own test BIN`() {
        assertThat(SyntheticPanGenerator.generate(CardNetwork.VISA).pan).startsWith("411111")
        assertThat(SyntheticPanGenerator.generate(CardNetwork.MASTERCARD).pan).startsWith("555555")
        assertThat(SyntheticPanGenerator.generate(CardNetwork.AMEX).pan).startsWith("378282")
        assertThat(SyntheticPanGenerator.generate(CardNetwork.UNIONPAY).pan).startsWith("621234")
    }

    // The correctness fix: the mask used to be a random 4-digit number matching no real PAN.
    @Test fun `the masked PAN is derived from the PAN it was generated with`() {
        repeat(REPEATS) {
            val credential = SyntheticPanGenerator.generate(CardNetwork.VISA)
            assertThat(credential.maskedPan).isEqualTo("**** **** **** ${credential.pan.takeLast(4)}")
            assertThat(credential.maskedPan).doesNotContain(credential.pan.dropLast(4))
        }
    }

    @Test fun `PANs are not repeated`() {
        val pans = (1..REPEATS).map { SyntheticPanGenerator.generate(CardNetwork.VISA).pan }
        assertThat(pans.toSet()).hasSize(pans.size)
    }

    @Test fun `isLuhnValid rejects a tampered PAN and a non numeric string`() {
        val pan = SyntheticPanGenerator.generate(CardNetwork.VISA).pan
        val lastDigit = pan.last().digitToInt()
        val tampered = pan.dropLast(1) + ((lastDigit + 1) % 10)

        assertThat(SyntheticPanGenerator.isLuhnValid(tampered)).isFalse()
        assertThat(SyntheticPanGenerator.isLuhnValid("41111111111111x1")).isFalse()
        assertThat(SyntheticPanGenerator.isLuhnValid("")).isFalse()
    }

    private companion object {
        const val REPEATS = 50
    }
}
