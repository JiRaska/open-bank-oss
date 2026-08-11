// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.domain.model.EntityType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Content-level coverage for the OpenSanctions CSV row parser — name/alias/DOB/entity-type mapping
 * and RFC 4180 quoting. The integration tests in [SanctionsImportServiceTest] assert only the
 * change-set shape; *what* a row parses into lives here, where no HTTP server or mock is needed.
 */
class OpenSanctionsCsvColumnsTest {

    private val header = "id,schema,name,aliases,birth_date,countries,addresses,identifiers,sanctions," +
        "phones,emails,program_ids,dataset,first_seen,last_seen,last_change"
    private val idx = OpenSanctionsCsvColumns.from(OpenSanctionsCsvColumns.parseCsvLine(header))!!

    @Test
    fun `parses a person row with aliases, dob, nationality and default-empty programs`() {
        val row = "os-1,Person,Andrej Babis,\"Ondrej Babis;A. Babis\",1954-09-13,cz,,,,,,,,,,"

        val parsed = idx.parseRow(row)!!

        assertThat(parsed.id).isEqualTo("os-1")
        assertThat(parsed.name).isEqualTo("Andrej Babis")
        assertThat(parsed.aliases).containsExactlyInAnyOrder("Ondrej Babis", "A. Babis")
        assertThat(parsed.birthDate).isEqualTo("1954-09-13")
        assertThat(parsed.nationalities).containsExactly("cz")
        assertThat(parsed.entityType).isEqualTo(EntityType.INDIVIDUAL)
        assertThat(parsed.programs).isEmpty()
    }

    @Test
    fun `program_ids column wins over the caller default`() {
        val row = "os-2,Organization,Acme Sanctioned Co,,,,,,,,,\"EU-SANCTIONS\",,,,"

        val parsed = idx.parseRow(row)!!

        assertThat(parsed.entityType).isEqualTo(EntityType.ORGANIZATION)
        assertThat(parsed.programs).containsExactly("EU-SANCTIONS")
    }

    @Test
    fun `handles quoted CSV fields with embedded commas and escaped quotes`() {
        val row = "os-6,Person,\"Doe, John \"\"The Rock\"\"\",,,,,,,,,,,,,"

        val parsed = idx.parseRow(row)!!

        assertThat(parsed.name).isEqualTo("""Doe, John "The Rock"""")
    }

    @Test
    fun `maps vessel and aircraft schema types`() {
        val vessel = idx.parseRow("os-4,Vessel,MV Example,,,,,,,,,,,,,")!!
        val aircraft = idx.parseRow("os-5,Aircraft,N12345,,,,,,,,,,,,,")!!

        assertThat(vessel.entityType).isEqualTo(EntityType.VESSEL)
        assertThat(aircraft.entityType).isEqualTo(EntityType.AIRCRAFT)
    }

    @Test
    fun `returns null for a blank line or a nameless row`() {
        assertThat(idx.parseRow("")).isNull()
        assertThat(idx.parseRow("os-9,Person,,,,,,,,,,,,,,")).isNull()
    }

    @Test
    fun `from returns null when id or name column is missing`() {
        assertThat(OpenSanctionsCsvColumns.from(listOf("foo", "bar"))).isNull()
        assertThat(OpenSanctionsCsvColumns.from(listOf("id", "schema"))).isNull()
    }
}
