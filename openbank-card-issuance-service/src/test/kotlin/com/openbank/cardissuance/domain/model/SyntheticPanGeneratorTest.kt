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

    // ── generate(network, requiredLast4) — the vault backfill's entry point ────────────
    // The check digit IS the last digit, so pinning the last 4 means SOLVING for a body whose
    // Luhn check digit lands on the requested final digit — not appending one.

    @Test fun `a PAN generated for a required last 4 ends in those digits and stays Luhn valid`() {
        CardNetwork.entries.forEach { network ->
            LAST4_CASES.forEach { last4 ->
                val credential = SyntheticPanGenerator.generate(network, last4)
                assertThat(credential)
                    .describedAs("%s must produce a PAN ending in %s", network, last4)
                    .isNotNull()
                assertThat(credential!!.pan).endsWith(last4)
                assertThat(SyntheticPanGenerator.isLuhnValid(credential.pan))
                    .describedAs("%s PAN ending %s must satisfy Luhn", network, last4)
                    .isTrue()
            }
        }
    }

    @Test fun `a PAN generated for a required last 4 keeps the network BIN, length and mask`() {
        val credential = SyntheticPanGenerator.generate(CardNetwork.MASTERCARD, "3901")!!
        assertThat(credential.pan).startsWith("555555").hasSize(16)
        assertThat(credential.maskedPan).isEqualTo("**** **** **** 3901")

        val amex = SyntheticPanGenerator.generate(CardNetwork.AMEX, "0007")!!
        assertThat(amex.pan).startsWith("378282").hasSize(15).endsWith("0007")
        assertThat(amex.cvv).hasSize(4)
    }

    @Test fun `every required check digit 0-9 is reachable`() {
        (0..9).forEach { checkDigit ->
            val last4 = "129$checkDigit"
            val credential = SyntheticPanGenerator.generate(CardNetwork.VISA, last4)
            assertThat(credential).describedAs("last4 %s", last4).isNotNull()
            assertThat(SyntheticPanGenerator.isLuhnValid(credential!!.pan)).isTrue()
        }
    }

    @Test fun `only the hidden middle changes between two PANs built for the same last 4`() {
        val pans = (1..REPEATS).map { SyntheticPanGenerator.generate(CardNetwork.VISA, "3901")!!.pan }
        assertThat(pans).allMatch { it.endsWith("3901") }
        // Not a fixed number: the free middle is random, so repeated calls must not collide.
        assertThat(pans.toSet().size).isGreaterThan(1)
    }

    // A pre-vault maskedPan was a random string; anything that is not four digits must be REFUSED,
    // because the alternative is renumbering a card the customer has already read off the screen.
    @Test fun `an unusable required last 4 yields null rather than a different number`() {
        assertThat(SyntheticPanGenerator.generate(CardNetwork.VISA, null)).isNull()
        assertThat(SyntheticPanGenerator.generate(CardNetwork.VISA, "")).isNull()
        assertThat(SyntheticPanGenerator.generate(CardNetwork.VISA, "390")).isNull()
        assertThat(SyntheticPanGenerator.generate(CardNetwork.VISA, "39012")).isNull()
        assertThat(SyntheticPanGenerator.generate(CardNetwork.VISA, "39*1")).isNull()
        assertThat(SyntheticPanGenerator.generate(CardNetwork.VISA, "****")).isNull()
    }

    private companion object {
        const val REPEATS = 50
        val LAST4_CASES = listOf("0000", "3901", "1234", "9999", "0007", "5005")
    }
}
