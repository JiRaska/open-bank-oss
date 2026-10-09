// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.onboarding

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.infrastructure.catalog.StrategyCatalogRead
import com.openbank.pension.infrastructure.catalog.StrategyInstrumentCatalogResolver
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class StrategyInstrumentCatalogResolverTest {
    private val mapper = ObjectMapper()
    private val offeringId = UUID.randomUUID()
    private val revisionId = UUID.randomUUID()
    private val at = Instant.parse("2026-10-09T10:00:00Z")

    private fun json(value: String) = mapper.readTree(value)

    private fun revision(attributes: String, schemaVersion: Int = 2, state: String = "PUBLISHED") = json(
        """{"id":"$revisionId","offeringId":"$offeringId","number":7,
           "schemaRef":{"id":"org.openbank.retirement.pension-savings","version":$schemaVersion},
           "state":"$state","content":{"attributes":$attributes}}""",
    )

    private val validAttributes = """{"productLine":"DIP","jurisdictionPackId":"CZ/DIP",
        "fundStrategy":"DYNAMIC","reviewStatus":"LEGAL_AND_COMMERCIAL_REVIEWED",
        "instrumentClasses":["BOND_FUNDS","EQUITY_FUNDS"]}"""

    private fun resolver(
        ids: List<UUID> = listOf(offeringId),
        read: suspend (UUID, OffsetDateTime) -> JsonNode = { _, _ -> revision(validAttributes) },
    ) = StrategyInstrumentCatalogResolver(
        object : StrategyCatalogRead {
            override suspend fun offerings() = ids.map { json("""{"id":"$it"}""") }

            override suspend fun published(offeringId: UUID, at: OffsetDateTime) = read(offeringId, at)
        },
    )

    private fun lookup(resolver: StrategyInstrumentCatalogResolver) = runBlocking {
        resolver.effectivePublished("CZ", ProductLine.DIP, "DYNAMIC", at)
    }

    @Test
    fun `pins exact published effective revision and passes requested instant`() {
        var observed: OffsetDateTime? = null
        val result = lookup(
            resolver(read = { _, time ->
                observed = time
                revision(validAttributes)
            }),
        )
        assertThat(observed?.toInstant()).isEqualTo(at)
        assertThat(result).singleElement().satisfies {
            assertThat(it.revision).isEqualTo("$revisionId:7")
            assertThat(it.instrumentClasses).containsExactlyInAnyOrder("BOND_FUNDS", "EQUITY_FUNDS")
        }
    }

    @Test
    fun `missing effective revision is absent but denied catalog read fails`() {
        assertThat(lookup(resolver(read = { _, _ -> throw WebApplicationException(404) }))).isEmpty()
        assertThatThrownBy { lookup(resolver(read = { _, _ -> throw WebApplicationException(403) })) }
            .isInstanceOf(WebApplicationException::class.java)
    }

    @Test
    fun `unreviewed old schema empty classes and duplicate offering fail closed`() {
        assertThatThrownBy { lookup(resolver(read = { _, _ -> revision(validAttributes, schemaVersion = 1) })) }
            .hasMessageContaining("schema v2")
        val unreviewed = validAttributes.replace("LEGAL_AND_COMMERCIAL_REVIEWED", "DRAFT")
        assertThatThrownBy { lookup(resolver(read = { _, _ -> revision(unreviewed) })) }
            .hasMessageContaining("not reviewed")
        val emptyClasses = validAttributes.replace("\"BOND_FUNDS\",\"EQUITY_FUNDS\"", "")
        assertThatThrownBy { lookup(resolver(read = { _, _ -> revision(emptyClasses) })) }
            .hasMessageContaining("no instrument classes")
        assertThatThrownBy { lookup(resolver(ids = listOf(offeringId, offeringId))) }
            .hasMessageContaining("duplicate offerings")
    }

    @Test
    fun `scope mismatch and missing required attribute cannot become a mapping`() {
        assertThat(lookup(resolver(read = { _, _ -> revision(validAttributes.replace("CZ/DIP", "SK/DIP")) })))
            .isEmpty()
        val missingStrategy = validAttributes.replace("\"fundStrategy\":\"DYNAMIC\",", "")
        assertThatThrownBy { lookup(resolver(read = { _, _ -> revision(missingStrategy) })) }
            .hasMessageContaining("fundStrategy")
    }
}
