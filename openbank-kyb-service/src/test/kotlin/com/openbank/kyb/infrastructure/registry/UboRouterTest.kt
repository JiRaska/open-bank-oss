// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyb.application.port.out.KybMetricsPort
import com.openbank.kyb.application.port.out.RegistryUnavailableException
import com.openbank.kyb.application.port.out.UboAdapter
import com.openbank.kyb.domain.model.CountryPack
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboSource
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The routing decision that matters is what an unreachable register degrades to. Merging it into
 * SELF_DECLARATION produces a queue where a transient outage looks like a customer who owes the
 * bank a declaration — and nobody retries a customer.
 */
class UboRouterTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC)
    private val metrics = mockk<KybMetricsPort>(relaxed = true)
    private val gbId = LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456")

    private fun router(vararg adapters: UboAdapter): UboRouter {
        val instance = mockk<Instance<UboAdapter>>()
        every { instance.iterator() } answers { adapters.toMutableList().iterator() }
        return UboRouter().also {
            it.adapters = instance
            it.packs = CountryPackRegistry(ObjectMapper())
            it.metrics = metrics
            it.clock = clock
        }
    }

    private fun registerAdapter(
        scheme: IdentifierScheme = IdentifierScheme.GB_CRN,
        answer: (suspend () -> UboFinding?),
    ) = object : UboAdapter {
        override val source = "test-register"
        override fun supports(s: IdentifierScheme) = s == scheme
        override suspend fun lookup(identifier: LegalEntityIdentifier, pack: CountryPack) = answer()
    }

    @Test
    fun `an unreachable register degrades to UNAVAILABLE, never to SELF_DECLARATION`(): Unit = runBlocking {
        val fallback = SelfDeclarationUboAdapter().also { it.clock = clock }
        val broken = registerAdapter { throw RegistryUnavailableException("test-register") }

        val finding = router(broken, fallback).lookup(gbId)

        assertThat(finding.source).isEqualTo(UboSource.UNAVAILABLE)
        assertThat(finding.requiresDeclaration).isTrue()
        // The pack is still read, so the analyst sees which register was meant to answer.
        assertThat(finding.registerName).contains("Significant Control")
    }

    @Test
    fun `the register-backed adapter wins over the fallback, whatever the declaration order`(): Unit = runBlocking {
        val fallback = SelfDeclarationUboAdapter().also { it.clock = clock }
        val register = registerAdapter {
            UboFinding(gbId, UboSource.REGISTER, emptyList(), emptyList(), 0.25, "PSC", "ref", Instant.now(clock))
        }

        // Fallback declared FIRST: CDI iteration order is not something a bean can control, so the
        // router must sort rather than rely on it — the same reason RegistryRouter sorts.
        val finding = router(fallback, register).lookup(gbId)

        assertThat(finding.source).isEqualTo(UboSource.REGISTER)
    }

    @Test
    fun `a scheme no register adapter serves falls through to self-declaration`(): Unit = runBlocking {
        val fallback = SelfDeclarationUboAdapter().also { it.clock = clock }
        val czOnly = registerAdapter(IdentifierScheme.CZ_ICO) { null }

        val finding = router(czOnly, fallback).lookup(gbId)

        assertThat(finding.source).isEqualTo(UboSource.SELF_DECLARATION)
        assertThat(finding.requiresDeclaration).isTrue()
    }

    @Test
    fun `a jurisdiction with no country pack is UNAVAILABLE with the AMLD5 default threshold`(): Unit = runBlocking {
        val fallback = SelfDeclarationUboAdapter().also { it.clock = clock }

        val finding = router(fallback).lookup(LegalEntityIdentifier.of(IdentifierScheme.DE_HRB, "HRB12345"))

        assertThat(finding.source).isEqualTo(UboSource.UNAVAILABLE)
        assertThat(finding.threshold).isEqualTo(0.25)
        assertThat(finding.registerName).isNull()
    }
}
