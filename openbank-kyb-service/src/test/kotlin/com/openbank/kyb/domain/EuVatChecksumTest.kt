// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain

import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * EU VAT numbers carry a national check digit in most member states. Each case below is a
 * one-digit corruption of a valid number, so it must be rejected before VIES is asked.
 */
class EuVatChecksumTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "CZ45274649", // legal entity: CZ + IČO (ČEZ, a.s.)
            "CZ00006947", // legal entity with leading zeros
            "CZ7103192745", // natural person: CZ + 10-digit rodné číslo
            "CZ510101123", // natural person: CZ + 9-digit pre-1954 rodné číslo
            "CZ689127223", // "special" 9-digit DIČ (6xxxxxxxx) with its own check digit
            "SK2020317068", // SK: 10 digits divisible by 11
            "PL5260250274", // PL: NIP
            "DE136695976", // DE: ISO 7064 MOD 11,10
            "FR40303265045", // FR: key = (12 + 3 * (SIREN mod 97)) mod 97
            "ATU13585627", // AT: U + 8 digits, Luhn-like weighted sum
        ],
    )
    fun `accepts valid VAT numbers`(raw: String) {
        assertThat(LegalEntityIdentifier.parse(IdentifierScheme.EU_VAT, raw).isSuccess).describedAs(raw).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "CZ45274648", // IČO check digit wrong
            "CZ7103192746", // RČ not divisible by 11
            "CZ7113192745", // RČ month 13 does not exist
            "CZ1234567", // 7 digits: no CZ DIČ has that length
            "CZ95274649", // 8-digit DIČ never starts with 9
            "CZ689127224", // special DIČ check digit wrong (month 91: not a rodné číslo either)
            "SK2020317069",
            "PL5260250275",
            "DE136695977",
            "FR41303265045",
            "ATU13585628",
        ],
    )
    fun `rejects a one-digit corruption`(raw: String) {
        assertThat(LegalEntityIdentifier.parse(IdentifierScheme.EU_VAT, raw).isFailure).describedAs(raw).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["NL004495445B01", "IE6388047V", "GB980780684"])
    fun `countries without an implemented checksum still pass on format`(raw: String) {
        assertThat(LegalEntityIdentifier.parse(IdentifierScheme.EU_VAT, raw).isSuccess).describedAs(raw).isTrue()
    }
}
