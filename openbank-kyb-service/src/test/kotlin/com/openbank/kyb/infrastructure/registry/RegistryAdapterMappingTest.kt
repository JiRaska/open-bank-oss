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

/** Register JSON → [com.openbank.kyb.domain.model.RegistryExtract]. Fixtures mirror the real ARES v3 / GLEIF v1 shapes. */
class RegistryAdapterMappingTest {

    private val json = ObjectMapper()
    private val now = Instant.parse("2026-09-05T10:00:00Z")

    private fun fixture(name: String) = json.readTree(javaClass.getResourceAsStream("/fixtures/$name")!!)

    private val cz = CountryPackRegistry(json).packFor("CZ", java.time.LocalDate.of(2026, 9, 5))!!

    @Test
    fun `ARES sro maps current representatives, the current representation rule, seat and VAT id`() {
        val extract = AresRegistryAdapter().map(
            LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"),
            fixture("ares-subject-112.json"),
            fixture("ares-vr-112.json"),
            now,
            cz,
        )
        assertThat(extract.legalName).isEqualTo("Příklad s.r.o.")
        assertThat(extract.legalFormClass).isEqualTo(LegalFormClass.LIMITED_COMPANY)
        assertThat(extract.status).isEqualTo(EntityStatus.ACTIVE)
        assertThat(extract.taxId).isEqualTo("CZ45274649")
        assertThat(extract.registeredAddress?.city).isEqualTo("Praha")
        assertThat(extract.registeredAddress?.line1).isEqualTo("Hlavní 1, 110 00 Praha 1")
        assertThat(extract.incorporatedOn).isEqualTo(LocalDate.of(2010, 1, 15))
        // Petr Svoboda's membership ended and his entry carries datumVymazu: history, not a signer.
        assertThat(extract.representatives.map { it.fullName }).containsExactly("Ing. Jana Nováková", "Eva Dvořáková")
        assertThat(extract.representatives[0].dateOfBirth).isEqualTo(LocalDate.of(1980, 5, 5))
        assertThat(extract.representatives[0].role).isEqualTo("jednatel")
        assertThat(extract.representatives[0].body).isEqualTo("jednatelé")
        // The 2010 SOLE rule is deleted (datumVymazu); the 2020 two-jointly rule is current.
        assertThat(extract.representationRule.mode).isEqualTo(RepresentationMode.JOINT_N)
        assertThat(extract.representationRule.requiredSigners).isEqualTo(2)
        assertThat(extract.sourceRef).isEqualTo("Městský soud v Praze C 12345")
    }

    @Test
    fun `ARES sole trader has no public-register record and is their own sole representative`() {
        val subject = json.readTree(
            """{"ico":"12345679","obchodniJmeno":"Jan Novák","pravniForma":"101","datumVzniku":"2018-04-01","sidlo":{"kodStatu":"CZ","nazevObce":"Brno","psc":60200}}""",
        )
        val extract = AresRegistryAdapter().map(
            LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "12345679"),
            subject,
            null,
            now,
            cz,
        )
        assertThat(extract.legalFormClass).isEqualTo(LegalFormClass.SOLE_TRADER)
        assertThat(extract.isSoleTrader).isTrue()
        assertThat(extract.representationRule.mode).isEqualTo(RepresentationMode.SOLE)
        assertThat(extract.representatives).hasSize(1)
        assertThat(extract.representatives[0].fullName).isEqualTo("Jan Novák")
        assertThat(extract.representationRule.signaturesRequired(1)).isEqualTo(1)
    }

    @Test
    fun `a dissolved entity is DISSOLVED even when the VR record still lists people`() {
        val subject = (
            fixture(
                "ares-subject-112.json",
            ) as com.fasterxml.jackson.databind.node.ObjectNode
            ).put("datumZaniku", "2025-01-01")
        val extract = AresRegistryAdapter().map(
            LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"),
            subject,
            fixture("ares-vr-112.json"),
            now,
            cz,
        )
        assertThat(extract.status).isEqualTo(EntityStatus.DISSOLVED)
    }

    @Test
    fun `the CZ country pack classifies register legal-form codes and lists its schemes`() {
        assertThat(cz.classify("101")).isEqualTo(LegalFormClass.SOLE_TRADER)
        assertThat(cz.classify("112")).isEqualTo(LegalFormClass.LIMITED_COMPANY)
        assertThat(cz.classify("121")).isEqualTo(LegalFormClass.JOINT_STOCK)
        assertThat(cz.classify("205")).isEqualTo(LegalFormClass.COOPERATIVE)
        assertThat(cz.classify("999")).isEqualTo(LegalFormClass.OTHER)
        assertThat(cz.isSoleTrader("105")).isTrue()
        assertThat(cz.schemes.first()).isEqualTo(IdentifierScheme.CZ_ICO)
        assertThat(cz.registry.adapter).isEqualTo("ares")
        assertThat(cz.uboRegister.publicApi).isFalse()
        assertThat(cz.label("112", "cs")).isEqualTo("společnost s ručením omezeným")
        assertThat(
            CountryPackRegistry(json).packFor("CZ", java.time.LocalDate.of(2020, 1, 1)),
        ).describedAs("not yet effective").isNull()
        assertThat(CountryPackRegistry(json).packFor("XX", java.time.LocalDate.of(2026, 9, 5))).isNull()
    }

    @Test
    fun `GLEIF maps name, address, status and the national register id, with no representatives`() {
        val extract = GleifRegistryAdapter().map(
            LegalEntityIdentifier.of(IdentifierScheme.LEI, "529900T8BM49AURSDO55"),
            fixture("gleif-record.json"),
            now,
        )!!
        assertThat(extract.legalName).isEqualTo("Deutsche Bank Aktiengesellschaft")
        assertThat(extract.registeredAddress?.countryCode).isEqualTo("DE")
        assertThat(extract.registeredAddress?.line1).isEqualTo("Taunusanlage 12")
        assertThat(extract.status).isEqualTo(EntityStatus.ACTIVE)
        assertThat(extract.otherIdentifiers).containsEntry(IdentifierScheme.DE_HRB, "HRB30000")
        assertThat(extract.representatives).isEmpty()
        assertThat(extract.representationRule.mode).isEqualTo(RepresentationMode.UNKNOWN)
        assertThat(extract.sourceRef).isEqualTo("529900T8BM49AURSDO55")
    }
}
