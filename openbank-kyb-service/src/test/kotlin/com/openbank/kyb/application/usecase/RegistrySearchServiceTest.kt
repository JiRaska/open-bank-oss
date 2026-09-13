// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyb.application.port.`in`.DeclaredEntity
import com.openbank.kyb.application.port.`in`.SearchRegistryCommand
import com.openbank.kyb.application.port.out.BusinessRegistryPort
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RegistrySearchHit
import com.openbank.kyb.domain.model.RegistrySearchQuery
import com.openbank.kyb.domain.model.RegistrySearchResult
import com.openbank.kyb.infrastructure.registry.CountryPackRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Country → register routing and the query floor for name search (issue #9707). */
class RegistrySearchServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneOffset.UTC)

    private class RecordingRegistry(private val answer: RegistrySearchResult?) : BusinessRegistryPort {
        var seen: Pair<IdentifierScheme, RegistrySearchQuery>? = null
        var calls = 0
        override suspend fun lookup(identifier: LegalEntityIdentifier, declared: DeclaredEntity?): RegistryExtract? =
            null
        override suspend fun search(scheme: IdentifierScheme, query: RegistrySearchQuery): RegistrySearchResult? {
            calls++
            seen = scheme to query
            return answer
        }
    }

    private fun service(registry: BusinessRegistryPort) = RegistrySearchService().apply {
        this.registry = registry
        this.packs = CountryPackRegistry(ObjectMapper())
        this.clock = this@RegistrySearchServiceTest.clock
    }

    @Test
    fun `a CZ search routes to the CZ_ICO scheme with the trimmed terms`() {
        val registry = RecordingRegistry(
            RegistrySearchResult(
                listOf(
                    RegistrySearchHit(
                        LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "27074358"),
                        "Asseco Central Europe, a.s.",
                        "121",
                        "Praha 4",
                    ),
                ),
                totalMatches = 1,
            ),
        )

        val result = runBlocking {
            service(registry).search(SearchRegistryCommand(country = "CZ", name = "  Asseco  ", city = " Praha "))
        }

        assertThat(result?.hits).hasSize(1)
        assertThat(registry.seen?.first).isEqualTo(IdentifierScheme.CZ_ICO)
        assertThat(registry.seen?.second?.name).isEqualTo("Asseco")
        assertThat(registry.seen?.second?.city).isEqualTo("Praha")
    }

    @Test
    fun `a blank town is dropped rather than sent as an empty filter`() {
        val registry = RecordingRegistry(RegistrySearchResult.EMPTY)

        runBlocking { service(registry).search(SearchRegistryCommand(country = "CZ", name = "Kofola", city = "   ")) }

        assertThat(registry.seen?.second?.city).isNull()
    }

    @Test
    fun `a query below the floor answers empty WITHOUT calling the register`() {
        val registry = RecordingRegistry(RegistrySearchResult.EMPTY)

        val result = runBlocking { service(registry).search(SearchRegistryCommand(country = "CZ", name = "ab")) }

        assertThat(result).isEqualTo(RegistrySearchResult.EMPTY)
        assertThat(registry.calls)
            .describedAs(
                "a two-letter query matches tens of thousands of names; ARES rejects it, so the call buys nothing",
            )
            .isZero()
    }

    @Test
    fun `an unknown country answers null — no register, so no search box`() {
        val registry = RecordingRegistry(RegistrySearchResult.EMPTY)

        val result = runBlocking { service(registry).search(SearchRegistryCommand(country = "ZZ", name = "Anything")) }

        assertThat(result)
            .describedAs("null is 'cannot search'; an empty list would read as 'your company does not exist'")
            .isNull()
        assertThat(registry.calls).isZero()
    }

    @Test
    fun `the too-many outcome is passed through, not flattened into an empty result`() {
        val registry = RecordingRegistry(RegistrySearchResult.tooMany(2818))

        val result = runBlocking { service(registry).search(SearchRegistryCommand(country = "CZ", name = "stavby")) }

        assertThat(result?.tooManyMatches).isTrue()
        assertThat(result?.totalMatches).isEqualTo(2818)
        assertThat(result?.hits).isEmpty()
    }
}
