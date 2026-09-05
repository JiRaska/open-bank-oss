// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.importer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Issue #8362: the first-party EU FSF parser. The fixtures mirror the real FSF v1.1 element
 * model (attributes-only, `strong` name aliases, partial birthdates) — verified against the live
 * `xmlFullSanctionsList` export.
 */
class EuFsfSaxParserTest {

    private fun parse(xml: String) = EuFsfSaxParser.parse(xml.byteInputStream())

    @Test
    fun `a person entity parses with primary strong name, aliases, birthdate and citizenship`() {
        val entities = parse(
            """
            <export xmlns="http://eu.europa.ec/fpi/fsd/export" generationDate="2026-08-05T16:47:04.449+02:00" globalFileId="184961">
                <sanctionEntity designationDetails="" unitedNationId="" euReferenceNumber="EU.27.28" logicalId="13">
                    <remark>UNSC RESOLUTION 1483</remark>
                    <regulation regulationType="regulation" programme="IRQ" logicalId="348"/>
                    <subjectType code="person" classificationCode="P"/>
                    <nameAlias firstName="Saddam" middleName="" lastName="Hussein Al-Tikriti" wholeName="Saddam Hussein Al-Tikriti" strong="true" logicalId="17"/>
                    <nameAlias wholeName="Abu Ali" strong="true" logicalId="19"/>
                    <nameAlias wholeName="Abou Ali" nameLanguage="FR" strong="false" logicalId="380"/>
                    <citizenship countryIso2Code="IQ" countryDescription="IRAQ" logicalId="1"/>
                    <birthdate circa="false" birthdate="1937-04-28" city="al-Awja, near Tikrit" logicalId="1"/>
                </sanctionEntity>
            </export>
            """.trimIndent(),
        )

        assertThat(entities).hasSize(1)
        val entity = entities.single()
        assertThat(entity.logicalId).isEqualTo("13")
        assertThat(entity.subjectType).isEqualTo("person")
        assertThat(entity.primaryName).isEqualTo("Saddam Hussein Al-Tikriti")
        // Both other designations are aliases — the second strong one and the non-strong variant.
        assertThat(entity.aliases).containsExactly("Abu Ali", "Abou Ali")
        assertThat(entity.dateOfBirth).isEqualTo("1937-04-28")
        assertThat(entity.nationalities).containsExactly("IQ")
        assertThat(entity.programmes).containsExactly("IRQ")
    }

    @Test
    fun `an enterprise entity keeps its subject type verbatim`() {
        val entities = parse(
            """
            <export xmlns="http://eu.europa.ec/fpi/fsd/export">
                <sanctionEntity euReferenceNumber="EU.12345.67" logicalId="999">
                    <subjectType code="enterprise" classificationCode="E"/>
                    <nameAlias wholeName="ACME Trading s.r.o." strong="true" logicalId="1"/>
                    <regulation programme="TAQA" logicalId="2"/>
                </sanctionEntity>
            </export>
            """.trimIndent(),
        )

        assertThat(entities.single().subjectType).isEqualTo("enterprise")
        assertThat(entities.single().primaryName).isEqualTo("ACME Trading s.r.o.")
    }

    @Test
    fun `an entity with only non-strong names falls back to its first alias rather than being dropped`() {
        val entities = parse(
            """
            <export xmlns="http://eu.europa.ec/fpi/fsd/export">
                <sanctionEntity logicalId="555">
                    <subjectType code="person"/>
                    <nameAlias wholeName="Variant Spelling" strong="false" logicalId="1"/>
                </sanctionEntity>
            </export>
            """.trimIndent(),
        )

        assertThat(entities.single().primaryName).isEqualTo("Variant Spelling")
        assertThat(entities.single().aliases).isEmpty()
    }

    @Test
    fun `a composed name is used when wholeName is blank`() {
        val entities = parse(
            """
            <export xmlns="http://eu.europa.ec/fpi/fsd/export">
                <sanctionEntity logicalId="556">
                    <subjectType code="person"/>
                    <nameAlias firstName="Jane" middleName="Q" lastName="Public" wholeName="" strong="true" logicalId="1"/>
                </sanctionEntity>
            </export>
            """.trimIndent(),
        )

        assertThat(entities.single().primaryName).isEqualTo("Jane Q Public")
    }

    @Test
    fun `a partial or malformed birthdate is ignored rather than blocking the import`() {
        val entities = parse(
            """
            <export xmlns="http://eu.europa.ec/fpi/fsd/export">
                <sanctionEntity logicalId="557">
                    <subjectType code="person"/>
                    <nameAlias wholeName="Partial Date" strong="true" logicalId="1"/>
                    <birthdate circa="true" birthdate="1937-XX-XX" year="1937" logicalId="1"/>
                </sanctionEntity>
                <sanctionEntity logicalId="558">
                    <subjectType code="person"/>
                    <nameAlias wholeName="No Date" strong="true" logicalId="2"/>
                </sanctionEntity>
            </export>
            """.trimIndent(),
        )

        assertThat(entities).hasSize(2)
        assertThat(entities.map { it.dateOfBirth }).containsExactly(null, null)
    }

    @Test
    fun `an entity without a logicalId or any name is skipped`() {
        val entities = parse(
            """
            <export xmlns="http://eu.europa.ec/fpi/fsd/export">
                <sanctionEntity logicalId="">
                    <subjectType code="person"/>
                    <nameAlias wholeName="No Logical Id" strong="true" logicalId="1"/>
                </sanctionEntity>
                <sanctionEntity logicalId="559">
                    <subjectType code="person"/>
                </sanctionEntity>
            </export>
            """.trimIndent(),
        )

        assertThat(entities).isEmpty()
    }

    @Test
    fun `the parser streams the real export namespace without DOM-buffering assumptions`() {
        // Two entities with duplicate citizenships and programmes — de-duplicated per entity.
        val entities = parse(
            """
            <export xmlns="http://eu.europa.ec/fpi/fsd/export">
                <sanctionEntity logicalId="600">
                    <subjectType code="person"/>
                    <nameAlias wholeName="Dup Test" strong="true" logicalId="1"/>
                    <citizenship countryIso2Code="CZ" logicalId="1"/>
                    <citizenship countryIso2Code="CZ" logicalId="2"/>
                    <regulation programme="XYZ" logicalId="1"/>
                    <regulation programme="XYZ" logicalId="2"/>
                </sanctionEntity>
            </export>
            """.trimIndent(),
        )

        assertThat(entities.single().nationalities).containsExactly("CZ")
        assertThat(entities.single().programmes).containsExactly("XYZ")
    }
}
