// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.libs.domain.account.Iban
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CzechDomesticIbanTest {
    @Test
    fun `canonicalizes the Czech prefix-base representation used by customer edge`() {
        val result = CzechDomesticIban.fromAccountNumber(" 19-2000145399 ", "0800")

        assertThat(result).isEqualTo("CZ6508000000192000145399")
        assertThat(Iban.isValid(result!!)).isTrue()
    }

    @Test
    fun `canonicalizes a base account without a prefix`() {
        val result = CzechDomesticIban.fromAccountNumber("1234567890", "0800")

        assertThat(result).isEqualTo("CZ0708000000001234567890")
        assertThat(Iban.isValid(result!!)).isTrue()
    }

    @Test
    fun `rejects coordinates that cannot denote one Czech domestic account`() {
        assertThat(CzechDomesticIban.fromAccountNumber("19-20-00145399", "0800")).isNull()
        assertThat(CzechDomesticIban.fromAccountNumber("-2000145399", "0800")).isNull()
        assertThat(CzechDomesticIban.fromAccountNumber("19-2000145399", "80A0")).isNull()
        assertThat(CzechDomesticIban.fromAccountNumber("19-2000145399", "０800")).isNull()
        assertThat(CzechDomesticIban.fromAccountNumber("", "0800")).isNull()
        assertThat(CzechDomesticIban.fromAccountNumber("12345678901", "0800")).isNull()
    }
}
