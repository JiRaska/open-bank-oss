// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain

import com.openbank.kyb.domain.model.IdentityMatch
import com.openbank.kyb.domain.model.RegisteredAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IdentityMatchTest {

    @Test
    fun `the same person survives diacritics, word order and academic titles`() {
        assertThat(IdentityMatch.samePerson("Ing. Oldřich Vaněk, Ph.D.", "Vanek Oldrich")).isTrue()
        assertThat(IdentityMatch.samePerson("Mgr. Jana Nováková", "JANA NOVÁKOVÁ")).isTrue()
    }

    @Test
    fun `a different person is not matched, even a near one`() {
        assertThat(IdentityMatch.samePerson("Oldřich Vaněk", "Oldřich Vaníček")).isFalse()
        assertThat(IdentityMatch.samePerson("Jana Nováková", "Jan Novák")).isFalse()
        // An extra given name is a different identity claim, not a superset that passes.
        assertThat(IdentityMatch.samePerson("Oldřich Vaněk", "Oldřich Karel Vaněk")).isFalse()
    }

    @Test
    fun `a single name token never identifies anyone`() {
        assertThat(IdentityMatch.samePerson("Vaněk", "Vaněk")).isFalse()
        assertThat(IdentityMatch.samePerson("Ing. Vaněk", "Vaněk")).isFalse()
    }

    @Test
    fun `a sole trader business name may carry a trade suffix, but must contain the whole person`() {
        assertThat(IdentityMatch.soleTraderIs("Jan Novák - Truhlářství", "Jan Novák")).isTrue()
        assertThat(IdentityMatch.soleTraderIs("Jan Novák", "Petr Novák")).isFalse()
        assertThat(IdentityMatch.soleTraderIs("Novák Stavby", "Jan Novák")).isFalse()
    }

    @Test
    fun `an address conflict needs both sides known — absence is not mismatch`() {
        val prague = RegisteredAddress("Hlavní 1", "Praha", "110 00", "CZ")
        assertThat(IdentityMatch.addressConflicts(null, prague)).isFalse()
        assertThat(IdentityMatch.addressConflicts(prague, null)).isFalse()
        // Formatting of the postal code is not a difference.
        assertThat(
            IdentityMatch.addressConflicts(prague, RegisteredAddress("Jiná 5", "Praha 1", "11000", "CZ")),
        ).isFalse()
    }

    @Test
    fun `a different postal code, country or — without postal codes — city is a conflict`() {
        val prague = RegisteredAddress("Hlavní 1", "Praha", "11000", "CZ")
        assertThat(
            IdentityMatch.addressConflicts(prague, RegisteredAddress("Hlavní 1", "Brno", "60200", "CZ")),
        ).isTrue()
        assertThat(
            IdentityMatch.addressConflicts(prague, RegisteredAddress("Hlavní 1", "Praha", "11000", "SK")),
        ).isTrue()
        assertThat(
            IdentityMatch.addressConflicts(
                RegisteredAddress("Hlavní 1", "Praha", null, "CZ"),
                RegisteredAddress("Hlavní 1", "Brno", null, "CZ"),
            ),
        ).isTrue()
    }
}
