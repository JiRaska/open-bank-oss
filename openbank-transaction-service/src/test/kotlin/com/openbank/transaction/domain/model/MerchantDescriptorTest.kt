// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MerchantDescriptorTest {
    @Test
    fun `the same shop written three ways reaches one key`() {
        val a = MerchantDescriptor.normalise("ALZA.CZ A.S. PRAGUE CZ")
        val b = MerchantDescriptor.normalise("ALZA.CZ  PRAHA 4")
        val c = MerchantDescriptor.normalise("alza.cz a.s.")
        assertThat(a).isEqualTo("ALZACZ")
        assertThat(b).isEqualTo(a)
        assertThat(c).isEqualTo(a)
    }

    @Test
    fun `accents fold so PLZEN and PLZEŇ agree`() {
        assertThat(MerchantDescriptor.normalise("KAVÁRNA MŮJ ŠÁLEK PLZEŇ"))
            .isEqualTo(MerchantDescriptor.normalise("KAVARNA MUJ SALEK PLZEN"))
    }

    @Test
    fun `a legal form is dropped only as a whole token`() {
        // A shop actually called "As" must survive — the list holds tokens, not substrings.
        assertThat(MerchantDescriptor.normalise("AS BISTRO PRAHA")).isEqualTo("ASBISTRO")
    }

    @Test
    fun `a town is dropped from the end but kept inside a name`() {
        // "PRAHA COFFEE" is a trading name; "COFFEE PRAHA" is a shop with a location suffix.
        assertThat(MerchantDescriptor.normalise("PRAHA COFFEE")).isEqualTo("PRAHACOFFEE")
        assertThat(MerchantDescriptor.normalise("COFFEE PRAHA")).isEqualTo("COFFEE")
    }

    @Test
    fun `a district number is dropped only when it follows a town`() {
        assertThat(MerchantDescriptor.normalise("BILLA PRAHA 4")).isEqualTo("BILLA")
        // Standing alone the digits are part of the name — "PENNY 24" is not "PENNY" plus a district.
        assertThat(MerchantDescriptor.normalise("PENNY 24")).isEqualTo("PENNY24")
    }

    @Test
    fun `legal form town and country all strip in one pass`() {
        assertThat(MerchantDescriptor.normalise("ROHLIK GROUP A.S. PRAHA 4 CZ")).isEqualTo("ROHLIKGROUP")
    }

    @Test
    fun `a descriptor with nothing identifying left yields null`() {
        // Critically NOT the empty string: an empty key would match every other empty key and hand
        // one merchant's logo and coordinates to unrelated transactions.
        assertThat(MerchantDescriptor.normalise("PRAHA 4 CZ")).isNull()
        assertThat(MerchantDescriptor.normalise("A.S.")).isNull()
        assertThat(MerchantDescriptor.normalise("---")).isNull()
    }

    @Test
    fun `blank and null descriptors yield null`() {
        assertThat(MerchantDescriptor.normalise(null)).isNull()
        assertThat(MerchantDescriptor.normalise("")).isNull()
        assertThat(MerchantDescriptor.normalise("   ")).isNull()
    }

    @Test
    fun `two different merchants do not collide`() {
        // The whole safety argument for exact matching: near-neighbours must stay apart.
        assertThat(MerchantDescriptor.normalise("ALZA.CZ A.S."))
            .isNotEqualTo(MerchantDescriptor.normalise("ALZA VETERINARY PRAHA"))
    }

    @Test
    fun `a bank transfer description is not mistaken for a merchant`() {
        // It normalises to something, and that is fine — the catalogue simply will not hold it, so
        // the lookup misses and the transaction stays unenriched.
        assertThat(MerchantDescriptor.normalise("Nájem červen")).isEqualTo("NAJEMCERVEN")
    }

    @Test
    fun `normalisation is stable`() {
        val once = MerchantDescriptor.normalise("ALZA.CZ A.S. PRAHA 4 CZ")
        val twice = MerchantDescriptor.normalise(once)
        assertThat(twice).isEqualTo(once)
    }
}
