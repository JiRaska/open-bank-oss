// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.security

import com.openbank.libs.fincrime.CounterpartyRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class HmacCounterpartyKeyerTest {

    private fun keyer(key: String?, id: String? = "k1") =
        HmacCounterpartyKeyer(Optional.ofNullable(key), Optional.ofNullable(id))

    private val iban = "DE89370400440532013000"

    @Test
    fun `same identifier with different spacing and case gives the same pseudonym`() {
        val k = keyer("secret-a")
        assertThat(k.key(CounterpartyRef.fromIban("de89 3704 0044 0532 0130 00").account))
            .isEqualTo(k.key(CounterpartyRef.fromIban(iban).account))
    }

    @Test
    fun `pseudonym is versioned, carries the key id, and hides the identifier`() {
        val ref = keyer("secret-a").key(CounterpartyRef.fromIban(iban).account)
        assertThat(ref).matches("^cp2:k1:[0-9a-f]{64}$").doesNotContain("0532013000")
    }

    @Test
    fun `a different key or a different account gives a different pseudonym`() {
        val account = CounterpartyRef.fromIban(iban).account
        assertThat(keyer("secret-a").key(account)).isNotEqualTo(keyer("secret-b").key(account))
        assertThat(keyer("secret-a").key(account))
            .isNotEqualTo(keyer("secret-a").key(CounterpartyRef.fromIban("DE89370400440532013001").account))
    }

    @Test
    fun `no key, a blank key, or an invalid key id means no pseudonym - never an unkeyed hash`() {
        val account = CounterpartyRef.fromIban(iban).account
        assertThat(keyer(null).key(account)).isNull()
        assertThat(keyer("   ").key(account)).isNull()
        assertThat(keyer("secret-a", id = null).key(account)).isNull()
        assertThat(keyer("secret-a", id = "bad:id").key(account)).isNull()
    }
}
