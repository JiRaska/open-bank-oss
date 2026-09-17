// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application

import com.openbank.kyb.application.port.`in`.LookupCommand
import com.openbank.kyb.application.port.out.BusinessRegistryPort
import com.openbank.kyb.application.port.out.RegistryExtractCache
import com.openbank.kyb.application.usecase.RegistryLookupService
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Switching the sandbox demo off must stop it being served AT ONCE. The lookup cache holds an extract
 * for a day, so a cached demo extract would outlive the switch by up to 24 hours — hence it is never cached.
 */
class RegistryLookupDemoCacheTest {

    private val now = Instant.parse("2026-09-14T08:00:00Z")

    private fun extract(source: String, ico: String) = RegistryExtract(
        identifier = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, ico),
        legalName = "X",
        legalFormCode = "112",
        legalFormClass = LegalFormClass.LIMITED_COMPANY,
        status = EntityStatus.ACTIVE,
        registeredAddress = null,
        incorporatedOn = null,
        taxId = null,
        representatives = emptyList(),
        representationRule = RepresentationRule.SOLE,
        source = source,
        sourceRef = null,
        verification = ExtractVerification.VERIFIED,
        fetchedAt = now,
    )

    private fun service(answer: RegistryExtract, cache: RegistryExtractCache) = RegistryLookupService().also {
        it.registry = mockk<BusinessRegistryPort>().also { r -> coEvery { r.lookup(any(), any()) } returns answer }
        it.cache = cache
        it.clock = Clock.fixed(now, ZoneOffset.UTC)
        it.cacheTtl = Duration.ofHours(24)
    }

    @Test
    fun `the sandbox demo extract is served but never written to the cache`(): Unit = runBlocking {
        val cache = mockk<RegistryExtractCache>(relaxed = true)
        coEvery { cache.find(any(), any()) } returns null
        val demo = extract(RegistryExtract.SANDBOX_DEMO_SOURCE, "00000001")
        assertThat(service(demo, cache).lookup(demo.identifier, null)).isEqualTo(demo)
        coVerify(exactly = 0) { cache.put(any()) }
    }

    @Test
    fun `a real register extract is still cached`(): Unit = runBlocking {
        val cache = mockk<RegistryExtractCache>(relaxed = true)
        coEvery { cache.find(any(), any()) } returns null
        val real = extract("ares", "45274649")
        service(real, cache).lookup(real.identifier, null)
        coVerify(exactly = 1) { cache.put(real) }
    }

    @Test
    fun `authority freshness bypasses even a valid twenty-four-hour cache entry`(): Unit = runBlocking {
        val cache = mockk<RegistryExtractCache>(relaxed = true)
        val old = extract("ares", "45274649")
        val amended = old.copy(representationRule = RepresentationRule.SOLE.copy(sourceText = "amended rule"))
        coEvery { cache.find(any(), any()) } returns old
        val lookup = service(amended, cache)

        assertThat(lookup.fresh(LookupCommand(IdentifierScheme.CZ_ICO, "45274649"))).isEqualTo(amended)
        coVerify(exactly = 0) { cache.find(any(), any()) }
        coVerify(exactly = 0) { cache.put(any()) }
    }
}
