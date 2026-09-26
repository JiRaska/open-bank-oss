// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The published VR fixture carries no `fyzickaOsoba.adresa`; the live register does (measured against
 * ARES for a real company, 2026-09-14: `adresa` with `kodStatu`, `nazevObce`, `pscTxt`, `textovaAdresa`).
 * Without this mapping the address comparison would silently never run — every address "unknown".
 */
class AresRepresentativeAddressTest {

    private val adapter = AresRegistryAdapter()
    private val mapper = ObjectMapper()

    @Test
    fun `a statutory member's own address is mapped from the live VR shape`() {
        val person = mapper.readTree(
            """{"jmeno":"Jana","prijmeni":"Nováková","datumNarozeni":"1980-05-05",
               "adresa":{"kodStatu":"CZ","nazevObce":"Praha","pscTxt":"11000","textovaAdresa":"Hlavní 1, 11000 Praha"}}""",
        )
        val address = adapter.personAddress(person)
        assertThat(address).isNotNull
        assertThat(address!!.city).isEqualTo("Praha")
        assertThat(address.postalCode).isEqualTo("11000")
        assertThat(address.countryCode).isEqualTo("CZ")
    }

    @Test
    fun `no adresa means an unknown address, never an empty one that could look like a mismatch`() {
        val person = mapper.readTree("""{"jmeno":"Jana","prijmeni":"Nováková"}""")
        assertThat(adapter.personAddress(person)).isNull()
    }
}
