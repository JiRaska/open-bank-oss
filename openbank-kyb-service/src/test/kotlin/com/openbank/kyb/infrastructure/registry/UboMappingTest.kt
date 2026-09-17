// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.openbank.kyb.application.port.out.RegistryUnavailableException
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.OwnershipBand
import com.openbank.kyb.domain.model.UboSource
import com.openbank.kyb.infrastructure.rest.dto.UboResponse
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
        assertThat(finding.owners[0].sourceRecordRef)
            .isEqualTo("/company/OC123456/persons-with-significant-control/individual/psc-1")
        assertThat(UboResponse.from(finding).owners[0].sourceRecordRef)
            .isEqualTo(finding.owners[0].sourceRecordRef)
        assertThat(finding.owners[1].sourceRecordRef)
            .isEqualTo("/company/OC123456/persons-with-significant-control/corporate-entity/psc-2")
        assertThat(finding.owners[2].sourceRecordRef).isNull()
        assertThat(finding.owners[0].natureOfControl).hasSize(2)
        // The register publishes month and year only; a reconstructed day would be a fact nobody filed.
        assertThat(finding.owners[0].dateOfBirth).isNull()
        assertThat(finding.owners[1].corporate).isTrue()
        assertThat(finding.owners[1].registrationNumber).isEqualTo("12345678")
        assertThat(finding.owners[1].countryRegistered).isEqualTo("United Kingdom")
        assertThat(finding.owners[0].registrationNumber).isNull()
        // A control with no figure is UNQUANTIFIED, never below-threshold: the register named this
        // person for a reason, and below-threshold would drop them from reportableOwners.
        assertThat(finding.owners[2].band).isEqualTo(OwnershipBand.UNQUANTIFIED)
        assertThat(finding.reportableOwners).hasSize(3)
        assertThat(finding.requiresDeclaration).isFalse()
        assertThat(finding.threshold).isEqualTo(0.25)
    }

    @Test
    fun `a PSC reference cannot point to another company or arbitrary URL`() {
        val body = json.readTree(
            """
            {"total_results":3,"items":[
              {"name":"Synthetic owner", "links":{"self":"/company/OTHER/persons-with-significant-control/individual/1"}},
              {"name":"Synthetic owner 2", "links":{"self":"https://example.invalid/psc/2"}},
              {"name":"Synthetic owner 3", "links":{"self":"/company/OC123456/persons-with-significant-control/individual/../3"}}
            ]}
            """.trimIndent(),
        )
        val finding = adapter().map(LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456"), body, gb)

        assertThat(finding.owners).hasSize(3)
        assertThat(finding.owners.map { it.sourceRecordRef }).containsOnlyNulls()
    }

    @Test
    fun `corporate identifiers stay source evidence and malformed or personal fields are omitted`() {
        val body = json.readTree(
            """
            {"total_results":2,"items":[
              {"name":"Person", "kind":"individual-person-with-significant-control",
               "identification":{"registration_number":"12345678","country_registered":"United Kingdom"}},
              {"name":"Company", "kind":"corporate-entity-person-with-significant-control",
               "identification":{"registration_number":"../OTHER","country_registered":"United Kingdom"}}
            ]}
            """.trimIndent(),
        )
        val finding = adapter().map(LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456"), body, gb)
        assertThat(finding.owners.map { it.registrationNumber }).containsOnlyNulls()
        assertThat(finding.owners[0].countryRegistered).isNull()
        assertThat(finding.owners[1].countryRegistered).isEqualTo("United Kingdom")
    }

    @Test
    fun `a partial PSC page cannot be presented as a complete owner finding`() {
        val body = json.readTree(
            """{"total_results":2,"items":[{"name":"First owner","kind":"individual-person-with-significant-control"}]}""",
        )
        assertThatThrownBy {
            adapter().map(LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456"), body, gb)
        }.isInstanceOf(RegistryUnavailableException::class.java)
        val missingTotal = json.readTree("""{"items":[{"name":"First owner"}]}""")
        assertThatThrownBy {
            adapter().map(LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456"), missingTotal, gb)
        }.isInstanceOf(RegistryUnavailableException::class.java)
    }

    @Test
    fun `an unnamed active PSC cannot disappear from the owner finding`() {
        val body = json.readTree(
            """{"total_results":1,"items":[{"kind":"super-secure-person-with-significant-control"}]}""",
        )
        assertThatThrownBy {
            adapter().map(LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456"), body, gb)
        }.isInstanceOf(RegistryUnavailableException::class.java)
    }

    @Test
    fun `the wire response and OpenAPI expose a nullable source observation reference`() {
        val finding = adapter().map(
            LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456"),
            fixture("companies-house-psc.json"),
            gb,
        )
        val response = json.findAndRegisterModules().valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            UboResponse.from(finding),
        )
        val field = YAMLMapper().readTree(checkNotNull(javaClass.getResourceAsStream("/openapi.yaml")))
            .at("/components/schemas/BeneficialOwner/properties/sourceRecordRef")

        assertThat(response.at("/owners/0/sourceRecordRef").asText())
            .isEqualTo("/company/OC123456/persons-with-significant-control/individual/psc-1")
        assertThat(response.at("/owners/1/registrationNumber").asText()).isEqualTo("12345678")
        assertThat(response.at("/owners/1/countryRegistered").asText()).isEqualTo("United Kingdom")
        assertThat(response.at("/owners/2/sourceRecordRef").isNull).isTrue()
        assertThat(field.path("type").map { it.asText() }).containsExactly("string", "null")
    }

    @Test
    fun `a filed statement is an answer, not an absence`() {
        val body = json.readTree(
            """
            {"total_results":1,"items":[{"kind":"persons-with-significant-control-statement",
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
