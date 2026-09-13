// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CounterpartyKeyTest {
    @Test
    fun `a card payment keys off the acquirer descriptor, which is all it has`() {
        assertThat(CounterpartyKey.of(counterpartyName = null, description = "ALZA.CZ A.S. PRAHA 4 CZ"))
            .isEqualTo(MerchantDescriptor.normalise("ALZA.CZ A.S. PRAHA 4 CZ"))
    }

    @Test
    fun `a transfer keys off the counterparty name, not the payment reference`() {
        // This is the whole point of the feature. The reference changes every month; keying on it
        // would make the customer re-categorise the same rent twelve times a year.
        val september = CounterpartyKey.of("Novakova Jana", "Najem 09/2026")
        val october = CounterpartyKey.of("Novakova Jana", "Najem 10/2026")
        assertThat(september).isEqualTo(october)
        assertThat(september).isNotNull()
    }

    @Test
    fun `the name wins over the description when both are present`() {
        assertThat(CounterpartyKey.of("Novakova Jana", "ALZA.CZ"))
            .isEqualTo(MerchantDescriptor.normalise("Novakova Jana"))
    }

    @Test
    fun `nothing identifying yields null, never a shared empty key`() {
        // Every unidentifiable transaction collapsing onto one key would make them inherit each
        // other's category, which is worse than leaving them uncategorised.
        assertThat(CounterpartyKey.of(null, null)).isNull()
        assertThat(CounterpartyKey.of("", "   ")).isNull()
        assertThat(CounterpartyKey.of(null, "PRAHA 4")).isNull()
    }

    @Test
    fun `a name that normalises away falls back to the descriptor rather than to null`() {
        assertThat(CounterpartyKey.of("PRAHA 4", "ALZA.CZ"))
            .isEqualTo(MerchantDescriptor.normalise("ALZA.CZ"))
    }

    @Test
    fun `the key shares the merchant catalogue's normalisation, so an override shadows a catalogue entry`() {
        // If these two ever diverged, a customer's category on a shop would sit beside that shop's
        // catalogue row instead of taking precedence over it.
        val viaCatalogue = MerchantDescriptor.normalise("ALZA.CZ a.s.")
        assertThat(CounterpartyKey.of(null, "ALZA.CZ a.s.")).isEqualTo(viaCatalogue)
    }
}
