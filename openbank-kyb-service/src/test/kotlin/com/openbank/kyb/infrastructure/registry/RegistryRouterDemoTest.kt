// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.openbank.kyb.application.port.`in`.DeclaredEntity
import com.openbank.kyb.application.port.out.KybMetricsPort
import com.openbank.kyb.application.port.out.RegistryAdapter
import com.openbank.kyb.application.port.out.RegistryUnavailableException
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RegistrySearchHit
import com.openbank.kyb.domain.model.RegistrySearchQuery
import com.openbank.kyb.domain.model.RegistrySearchResult
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/** The router is where the demo switch lives, so the router is where "off means the real register" is proven. */
class RegistryRouterDemoTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC)
    private val demoIco = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, DemoEntity.ICO)
    private var aresLookups = 0

    private fun ares(searchAnswer: suspend () -> RegistrySearchResult?) = object : RegistryAdapter {
        override val source = "ares"
        override fun supports(scheme: IdentifierScheme) = scheme == IdentifierScheme.CZ_ICO
        override suspend fun lookup(identifier: LegalEntityIdentifier, declared: DeclaredEntity?): RegistryExtract? {
            aresLookups++
            return null
        }
        override suspend fun search(scheme: IdentifierScheme, query: RegistrySearchQuery) = searchAnswer()
    }

    private fun router(demoOn: Boolean, adapter: RegistryAdapter): RegistryRouter {
        val instance = mockk<Instance<RegistryAdapter>>()
        every { instance.iterator() } answers { mutableListOf(adapter).iterator() }
        return RegistryRouter().also {
            it.adapters = instance
            it.metrics = mockk<KybMetricsPort>(relaxed = true)
            it.demo = DemoEntity(demoOn, "Oldřich Vaněk", "Ukázková 1", "Praha", "11000", "CZ", Optional.empty(), clock)
        }
    }

    @Test
    fun `off, the demo IČO goes to the real register like any other`(): Unit = runBlocking {
        val r = router(demoOn = false, adapter = ares { RegistrySearchResult.EMPTY })
        assertThat(r.lookup(demoIco, null)).isNull()
        assertThat(aresLookups).isEqualTo(1)
        assertThat(r.search(IdentifierScheme.CZ_ICO, RegistrySearchQuery("OpenBank Demo"))?.hits).isEmpty()
    }

    @Test
    fun `on, the demo IČO is answered without asking the register`(): Unit = runBlocking {
        val r = router(demoOn = true, adapter = ares { RegistrySearchResult.EMPTY })
        val ex = r.lookup(demoIco, null)
        assertThat(ex?.source).isEqualTo(RegistryExtract.SANDBOX_DEMO_SOURCE)
        assertThat(aresLookups).isZero()
    }

    @Test
    fun `on, a demo search lists the demo first and keeps the real hits`(): Unit = runBlocking {
        val real = RegistrySearchHit(
            LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"),
            "Demo Stavby a.s.",
            "121",
            "Praha",
        )
        val r = router(demoOn = true, adapter = ares { RegistrySearchResult(listOf(real), 1) })
        val result = r.search(IdentifierScheme.CZ_ICO, RegistrySearchQuery("demo"))
        assertThat(result?.hits?.map { it.name }).containsExactly(DemoEntity.LEGAL_NAME, "Demo Stavby a.s.")
        assertThat(result?.totalMatches).isEqualTo(2)
    }

    @Test
    fun `on, a demo search still works when the register is down`(): Unit = runBlocking {
        val r = router(demoOn = true, adapter = ares { throw RegistryUnavailableException("ares down") })
        val result = r.search(IdentifierScheme.CZ_ICO, RegistrySearchQuery("OpenBank Demo"))
        assertThat(result?.hits?.map { it.name }).containsExactly(DemoEntity.LEGAL_NAME)
    }

    @Test
    fun `on, a search that does not mean the demo is untouched`(): Unit = runBlocking {
        val r = router(demoOn = true, adapter = ares { RegistrySearchResult.EMPTY })
        assertThat(r.search(IdentifierScheme.CZ_ICO, RegistrySearchQuery("Škoda Auto"))?.hits).isEmpty()
    }
}
