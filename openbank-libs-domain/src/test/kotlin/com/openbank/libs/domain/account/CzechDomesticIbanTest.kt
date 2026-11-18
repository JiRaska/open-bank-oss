// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.account

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CzechDomesticIbanTest {
    @Test
    fun `checksums a Czech account without a prefix`() {
        val iban = CzechDomesticIban.fromAccountNumber("123456789", "0100")
        assertThat(iban).isEqualTo("CZ1801000000000123456789")
        assertThat(Iban.isValid(requireNotNull(iban))).isTrue()
    }

    @Test
    fun `pads a short prefix and short base independently`() {
        val iban = CzechDomesticIban.fromAccountNumber("19-25", "0800")
        assertThat(iban).isEqualTo("CZ5108000000190000000025")
        assertThat(Iban.isValid(requireNotNull(iban))).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["1234567-25", "19-12345678901", "19-", "-25", "19-2-5", "ą5"])
    fun `rejects malformed national fields`(account: String) {
        assertThat(CzechDomesticIban.fromAccountNumber(account, "0800")).isNull()
    }
}
