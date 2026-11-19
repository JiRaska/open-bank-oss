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

    // Every value below is SYNTHETIC: constructed to satisfy (or break) the published algorithm,
    // not taken from any register. The rodné čísla are dated 2053 and 1900 so no living person holds them.
    @ParameterizedTest
    @ValueSource(
        strings = [
            "CZ12345679", // legal entity: CZ + IČO
            "CZ00000124", // legal entity with leading zeros
            "CZ5301011232", // natural person: CZ + 10-digit rodné číslo
            "CZ000101001", // natural person: CZ + 9-digit pre-1954 rodné číslo
            "CZ699127220", // "special" 9-digit DIČ (6xxxxxxxx) with its own check digit
            "SK1983589520", // SK: 10 digits, first not 0, third in 2,3,4,7,8,9, divisible by 11
            "PL1234561001", // PL: NIP
            "DE123456788", // DE: ISO 7064 MOD 11,10
            "FR11123456782", // FR: numeric key = (12 + 3 * (SIREN mod 97)) mod 97
            "FR90000012345", // FR Monaco: SIREN 000..., no Luhn digit
            "FRAB123456782", // FR alphanumeric key: format only
            "ATU12345675", // AT: U + 8 digits
        ],
    )
    fun `accepts valid VAT numbers`(raw: String) {
        assertThat(LegalEntityIdentifier.parse(IdentifierScheme.EU_VAT, raw).isSuccess).describedAs(raw).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "CZ12345678", // IČO check digit wrong
            "CZ5301011233", // RČ not divisible by 11
            "CZ5313011232", // RČ month 13 does not exist
            "CZ1234567", // 7 digits: no CZ DIČ has that length
            "CZ91234565", // 8-digit DIČ never starts with 9 (IČO checksum itself holds)
            "CZ699127221", // special DIČ check digit wrong (month 91: not a rodné číslo either)
            "SK1983589521", // SK: not divisible by 11
            "SK9309064974", // SK: divisible by 11 but third digit is 0
            "SK0895446915", // SK: divisible by 11, third digit ok, first digit 0
            "SK2000000002", // SK: divisible by 11, third digit 0 (accepted before #16774)
            "PL1234561002",
            "DE123456789",
            "FR12123456782", // numeric key wrong
            "FR11123456783", // SIREN fails Luhn
            "FRAB123456783", // alphanumeric key still needs a Luhn-valid SIREN
            "ATU12345676",
        ],
    )
    fun `rejects a one-digit corruption`(raw: String) {
        assertThat(LegalEntityIdentifier.parse(IdentifierScheme.EU_VAT, raw).isFailure).describedAs(raw).isTrue()
    }

    @org.junit.jupiter.api.Test
    fun `a stored row restores even when a later rule would reject it`() {
        // Persisted before VAT check digits were enforced; reads must not break onboarded cases.
        val restored = LegalEntityIdentifier.restore(IdentifierScheme.EU_VAT, "CZ12345678")
        assertThat(restored.value).isEqualTo("CZ12345678")
        assertThat(runCatching { LegalEntityIdentifier.restore(IdentifierScheme.EU_VAT, "cz 1") }.isFailure).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["GB123456789", "GR123456783", "XX12345678", "CH12345678"])
    fun `a prefix with no rule is rejected, never passed on format`(raw: String) {
        assertThat(LegalEntityIdentifier.parse(IdentifierScheme.EU_VAT, raw).isFailure).describedAs(raw).isTrue()
    }
}
