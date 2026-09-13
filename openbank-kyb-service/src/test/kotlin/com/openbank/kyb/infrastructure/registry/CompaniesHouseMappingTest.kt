// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RepresentationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Companies House JSON → RegistryExtract. Fixtures mirror the real
 * `GET /company/{number}` and `GET /company/{number}/officers` shapes (ADR-0284 D2).
 */
class CompaniesHouseMappingTest {

    private val json = ObjectMapper()
    private val now = Instant.parse("2026-09-05T10:00:00Z")
    private val gb = CountryPackRegistry(json).packFor("GB", LocalDate.of(2026, 9, 5))!!

    private fun fixture(name: String) = json.readTree(javaClass.getResourceAsStream("/fixtures/$name")!!)

    @Test
    fun `a live ltd maps its directors, and only the ones who can bind it`() {
        val extract = CompaniesHouseRegistryAdapter().map(
            LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456"),
            fixture("companies-house-profile-ltd.json"),
            fixture("companies-house-officers.json"),
            now,
            gb,
        )
        assertThat(extract.legalName).isEqualTo("EXAMPLE TRADING LIMITED")
        assertThat(extract.legalFormClass).isEqualTo(LegalFormClass.LIMITED_COMPANY)
        assertThat(extract.status).isEqualTo(EntityStatus.ACTIVE)
        assertThat(extract.incorporatedOn).isEqualTo(LocalDate.of(2011, 6, 14))
        assertThat(extract.registeredAddress?.line1).isEqualTo("1 Example Street, Floor 4")
        assertThat(extract.registeredAddress?.postalCode).isEqualTo("EC1A 1BB")
        // A resigned director is history and a secretary cannot bind the company: offering either
        // as a signer produces a contract the register does not support.
        assertThat(extract.representatives.map { it.fullName })
            .containsExactly("SMITH, Jane Elizabeth", "PATEL, Rajesh")
        // Companies House publishes month and year only. Nothing reconstructs a date of birth.
        assertThat(extract.representatives.map { it.dateOfBirth }).containsOnlyNulls()
        assertThat(extract.representatives[0].role).isEqualTo("director")
        // No representation rule exists in the UK register; the model articles let any single
        // director bind the company, so this is derived and not read.
        assertThat(extract.representationRule.mode).isEqualTo(RepresentationMode.SOLE)
        assertThat(extract.representationRule.signaturesRequired(2)).isEqualTo(1)
    }

    @Test
    fun `a company whose officers could not be read is UNKNOWN, not SOLE`() {
        // The officers call 404s or the list is empty: we cannot name a signer, so the case must
        // reach a human rather than accept whoever asked.
        val extract = CompaniesHouseRegistryAdapter().map(
            LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456"),
            fixture("companies-house-profile-ltd.json"),
            null,
            now,
            gb,
        )
        assertThat(extract.representatives).isEmpty()
        assertThat(extract.representationRule.mode).isEqualTo(RepresentationMode.UNKNOWN)
    }

    @Test
    fun `company status maps onto the four states the domain has`() {
        fun statusOf(raw: String) = CompaniesHouseRegistryAdapter().map(
            LegalEntityIdentifier.of(IdentifierScheme.GB_CRN, "OC123456"),
            json.readTree("""{"company_name":"X","type":"ltd","company_status":"$raw"}"""),
            null,
            now,
            gb,
        ).status

        assertThat(statusOf("active")).isEqualTo(EntityStatus.ACTIVE)
        assertThat(statusOf("liquidation")).isEqualTo(EntityStatus.IN_LIQUIDATION)
        assertThat(statusOf("administration")).isEqualTo(EntityStatus.INSOLVENT)
        assertThat(statusOf("dissolved")).isEqualTo(EntityStatus.DISSOLVED)
    }

    @Test
    fun `the GB pack declares that its register carries no representation rule`() {
        // The absence is the interesting fact: a pack with a parser name would send this extract
        // through a Czech-shaped text parser that has nothing to read.
        assertThat(gb.representationRuleParser).isNull()
        assertThat(gb.registry.listsRepresentationRule).isFalse()
        assertThat(gb.registry.listsRepresentatives).isTrue()
        assertThat(gb.classify("plc")).isEqualTo(LegalFormClass.JOINT_STOCK)
        assertThat(gb.classify("llp")).isEqualTo(LegalFormClass.PARTNERSHIP)
        assertThat(gb.classify("uk-establishment")).isEqualTo(LegalFormClass.BRANCH)
        // An unknown company type must not be silently treated as a limited company.
        assertThat(gb.classify("something-new")).isEqualTo(LegalFormClass.OTHER)
    }

    @Test
    fun `the two packs are loaded independently and neither shadows the other`() {
        val registry = CountryPackRegistry(json)
        assertThat(registry.packs.map { it.country }).containsExactlyInAnyOrder("CZ", "GB")
        assertThat(registry.packFor("CZ", LocalDate.of(2026, 9, 5))?.representationRuleParser).isEqualTo("cz")
        assertThat(registry.packFor("DE", LocalDate.of(2026, 9, 5))).isNull()
        // Before the effective date neither pack applies — the effective-dating is real, not decorative.
        assertThat(registry.packFor("GB", LocalDate.of(2026, 8, 31))).isNull()
    }
}
