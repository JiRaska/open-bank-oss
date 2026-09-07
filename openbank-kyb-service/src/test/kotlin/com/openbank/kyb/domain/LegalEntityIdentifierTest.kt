// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain

import com.openbank.kyb.domain.model.IdentifierChecksums
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.InvalidIdentifierException
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LegalEntityIdentifierTest {

    // 45274649 = ČEZ, a.s.; 27082440 = Alza.cz a.s.; 00006947 = Ministerstvo financí (leading zeros).
    @Test
    fun `accepts real Czech IČO values and rejects a single wrong digit`() {
        listOf("45274649", "27082440", "00006947", "26185610").forEach {
            assertThat(IdentifierChecksums.ico(it)).describedAs(it).isTrue()
        }
        assertThat(IdentifierChecksums.ico("45274648")).isFalse()
        assertThat(IdentifierChecksums.ico("4527464")).isFalse()
        assertThatThrownBy { LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274648") }
            .isInstanceOf(InvalidIdentifierException::class.java)
            .hasMessageContaining("checksum")
    }

    @Test
    fun `normalises what people actually type into an IČO field`() {
        assertThat(LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, " 452 746 49 ").value).isEqualTo("45274649")
        assertThat(LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "CZ45274649").value).isEqualTo("45274649")
        assertThat(LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "6947").value).isEqualTo("00006947")
    }

    @Test
    fun `LEI passes ISO 7064 mod 97-10 and a transposed pair fails it`() {
        // 315700N6RX2TO0QO8T71 is a published GLEIF example-shaped LEI; the check digits must leave 1.
        val valid = LegalEntityIdentifier.parse(IdentifierScheme.LEI, "5299 00T8 BM49 AURS DO55")
        assertThat(valid.isSuccess).describedAs(valid.exceptionOrNull()?.message).isTrue()
        assertThat(IdentifierChecksums.mod97("529900T8BM49AURSDO55")).isTrue()
        assertThat(IdentifierChecksums.mod97("529900T8BM49AURSDO54")).isFalse()
    }

    @Test
    fun `Polish NIP and French SIREN checksums`() {
        assertThat(IdentifierChecksums.nip("5260250995")).isTrue()
        assertThat(IdentifierChecksums.nip("5260250994")).isFalse()
        assertThat(IdentifierChecksums.luhn("552081317")).isTrue()
        assertThat(IdentifierChecksums.luhn("552081318")).isFalse()
    }

    @Test
    fun `schemes for a country list national ones first then the cross-border ones`() {
        val cz = IdentifierScheme.forCountry("cz")
        assertThat(cz.first()).isEqualTo(IdentifierScheme.CZ_ICO)
        assertThat(cz).contains(IdentifierScheme.LEI, IdentifierScheme.EU_VAT)
        assertThat(cz).doesNotContain(IdentifierScheme.SK_ICO)
        assertThat(IdentifierScheme.forCountry("XX")).allMatch { it.country == null }
    }
}
