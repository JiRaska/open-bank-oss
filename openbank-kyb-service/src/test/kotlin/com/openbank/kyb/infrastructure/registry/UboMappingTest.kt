// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.OwnershipBand
import com.openbank.kyb.domain.model.UboSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * PSC register JSON → [com.openbank.kyb.domain.model.UboFinding] (ADR-0284 D5), and the
 * self-declaration fallback beside it.
 */
class UboMappingTest {

    private val json = ObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC)
    private val packs = CountryPackRegistry(json)
    private val gb = packs.packFor("GB", LocalDate.of(2026, 9, 5))!!
    private val cz = packs.packFor("CZ", LocalDate.of(2026, 9, 5))!!

    private fun fixture(name: String) = json.readTree(javaClass.getResourceAsStream("/fixtures/$name")!!)

    private fun adapter() = CompaniesHousePscAdapter().also { it.clock = clock }

    @Test
    fun `a ceased PSC is history and the strongest stated band wins`() {
        val finding = adapter().map(
            LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456"),
            fixture("companies-house-psc.json"),
            gb,
        )

        assertThat(finding.source).isEqualTo(UboSource.REGISTER)
        assertThat(finding.owners.map { it.fullName })
            .containsExactly("SMITH, Jane Elizabeth", "HOLDINGS EXAMPLE LIMITED", "DOE, Alex")
        // Shares 50-75% and votes 25-50% for the same person: the threshold test is about the
        // higher one, so a mapping that took the first or the last would understate her.
        assertThat(finding.owners[0].band).isEqualTo(OwnershipBand.PCT_50_TO_75)
        assertThat(finding.owners[0].natureOfControl).hasSize(2)
        // The register publishes month and year only; a reconstructed day would be a fact nobody filed.
        assertThat(finding.owners[0].dateOfBirth).isNull()
        assertThat(finding.owners[1].corporate).isTrue()
        // A control with no figure is UNQUANTIFIED, never below-threshold: the register named this
        // person for a reason, and below-threshold would drop them from reportableOwners.
        assertThat(finding.owners[2].band).isEqualTo(OwnershipBand.UNQUANTIFIED)
        assertThat(finding.reportableOwners).hasSize(3)
        assertThat(finding.requiresDeclaration).isFalse()
        assertThat(finding.threshold).isEqualTo(0.25)
    }

    @Test
    fun `a filed statement is an answer, not an absence`() {
        val body = json.readTree(
            """
            {"items":[{"kind":"persons-with-significant-control-statement",
                       "statement":"no-individual-or-entity-with-signficant-control"}]}
            """.trimIndent(),
        )

        val finding = adapter().map(LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456"), body, gb)

        // Empty owners AND a statement: collapsing the two would show an analyst the same screen as
        // a company that simply has not filed, which is a different problem.
        assertThat(finding.owners).isEmpty()
        assertThat(finding.registerStatements).hasSize(1)
        assertThat(finding.source).isEqualTo(UboSource.REGISTER)
        assertThat(finding.requiresDeclaration).isFalse()
    }

    @Test
    fun `a jurisdiction with no queryable register yields a self-declaration, not an empty register answer`(): Unit =
        runBlocking {
            val finding = SelfDeclarationUboAdapter().also { it.clock = clock }
                .lookup(LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"), cz)

            assertThat(finding.source).isEqualTo(UboSource.SELF_DECLARATION)
            assertThat(finding.requiresDeclaration).isTrue()
            assertThat(finding.owners).isEmpty()
            // The pack is what says CZ has no public API; the adapter reads it rather than knowing it.
            assertThat(cz.uboRegister.publicApi).isFalse()
            assertThat(finding.registerName).isEqualTo(cz.uboRegister.name)
            assertThat(finding.threshold).isEqualTo(0.25)
        }

    @Test
    fun `the fallback does not invent an owner from the directors`(): Unit = runBlocking {
        // A director is not a beneficial owner. The two coincide often enough for the mistake to
        // look right in testing, and a fabricated UBO is worse than a missing one: it satisfies the
        // evidence requirement while being unevidenced.
        val finding = SelfDeclarationUboAdapter().also { it.clock = clock }
            .lookup(LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"), cz)

        assertThat(finding.owners).isEmpty()
        assertThat(finding.reportableOwners).isEmpty()
    }

    @Test
    fun `the GB pack declares a queryable UBO register and the CZ pack does not`() {
        assertThat(gb.uboRegister.publicApi).isTrue()
        assertThat(gb.uboRegister.name).contains("Significant Control")
        assertThat(cz.uboRegister.fallback).isEqualTo("SELF_DECLARATION")
    }
}
