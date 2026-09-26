// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.openbank.kyb.application.port.`in`.DeclaredEntity
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * #9038: a declared legal form class that does not parse must be a 400-boundary error, not a
 * silently persisted `OTHER` — a typo in the applicant's legal form was written as a wrong fact.
 */
class ManualAttestationRegistryAdapterTest {

    private val adapter = ManualAttestationRegistryAdapter().apply {
        clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC)
    }

    private val ico = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649")

    private fun declared(legalFormClass: String?) = DeclaredEntity(
        legalName = "Příklad s.r.o.",
        countryCode = "cz",
        legalFormClass = legalFormClass,
        addressLine1 = "Hlavní 1",
        city = "Praha",
        postalCode = "11000",
    )

    @Test
    fun `an absent legal form class still maps to OTHER`(): Unit = runBlocking {
        assertThat(adapter.lookup(ico, declared(null))!!.legalFormClass).isEqualTo(LegalFormClass.OTHER)
    }

    @Test
    fun `a valid declared legal form class is honoured`(): Unit = runBlocking {
        assertThat(adapter.lookup(ico, declared("LIMITED_COMPANY"))!!.legalFormClass)
            .isEqualTo(LegalFormClass.LIMITED_COMPANY)
    }

    @Test
    fun `an unparseable declared legal form class throws, never degrades to OTHER`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { adapter.lookup(ico, declared("LIMITED_COMPNAY")) }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("LIMITED_COMPNAY")
            .hasMessageContaining("LIMITED_COMPANY")
    }
}
