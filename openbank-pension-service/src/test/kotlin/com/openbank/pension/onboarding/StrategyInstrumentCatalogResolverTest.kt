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
    private val approvalDigest = "b".repeat(64)

    private fun json(value: String) = mapper.readTree(value)

    private fun revision(attributes: String, schemaVersion: Int = 2, state: String = "PUBLISHED") = json(
        """{"id":"$revisionId","offeringId":"$offeringId","number":7,
           "schemaRef":{"id":"org.openbank.retirement.pension-savings","version":$schemaVersion},
           "state":"$state","makerId":"maker","checkerId":"checker","reason":"reviewed revision",
           "contentHash":"${"a".repeat(64)}","pensionApprovalDigest":"$approvalDigest",
           "effectiveFrom":"2026-10-09T00:00:00Z","effectiveTo":"2026-10-10T00:00:00Z",
           "content":{"attributes":$attributes}}""",
    )

    private fun approval(role: String, digest: String = approvalDigest) =
        json("""{"role":"$role","digest":"$digest","approvedAt":"2026-10-08T12:00:00Z"}""")

    private fun approvals() = listOf(approval("LEGAL_COUNSEL"), approval("PRODUCT_OWNER"))

    private val validAttributes = """{"productLine":"DIP","jurisdictionPackId":"CZ/DIP",
        "fundStrategy":"DYNAMIC","reviewStatus":"LEGAL_AND_COMMERCIAL_REVIEWED",
        "instrumentClasses":["BOND_FUNDS","EQUITY_FUNDS"]}"""

    private fun resolver(
        ids: List<UUID> = listOf(offeringId),
        read: suspend (UUID, OffsetDateTime) -> JsonNode = { _, _ -> revision(validAttributes) },
        readApprovals: suspend (UUID, UUID) -> List<JsonNode> = { _, _ -> approvals() },
    ) = StrategyInstrumentCatalogResolver(
        object : StrategyCatalogRead {
            override suspend fun offerings() = ids.map { json("""{"id":"$it"}""") }

            override suspend fun published(offeringId: UUID, at: OffsetDateTime) = read(offeringId, at)

            override suspend fun pensionApprovals(offeringId: UUID, revisionId: UUID) =
                readApprovals(offeringId, revisionId)
        },
    )

    private fun lookup(resolver: StrategyInstrumentCatalogResolver) = runBlocking {
        resolver.effectivePublished("CZ", ProductLine.DIP, "DYNAMIC", at)
    }

    @Test
    fun `pins exact published effective revision and passes requested instant`() {
        var observed: OffsetDateTime? = null
        var approvedRevision: UUID? = null
        val result = lookup(
            resolver(
                read = { _, time ->
                    observed = time
                    revision(validAttributes)
                },
                readApprovals = { id, rev ->
                    assertThat(id).isEqualTo(offeringId)
                    approvedRevision = rev
                    approvals()
                },
            ),
        )
        assertThat(observed?.toInstant()).isEqualTo(at)
        assertThat(approvedRevision).isEqualTo(revisionId)
        val mapping = result.single()
        assertThat(mapping.revision).isEqualTo("$revisionId:7")
        assertThat(mapping.instrumentClasses).containsExactlyInAnyOrder("BOND_FUNDS", "EQUITY_FUNDS")
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
    fun `maker authored review flag without catalog publication evidence fails closed`() {
        val approved = revision(validAttributes)
        listOf("checkerId", "reason", "contentHash").forEach { field ->
            val missing = approved.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply { remove(field) }
            assertThatThrownBy { lookup(resolver(read = { _, _ -> missing })) }
                .hasMessageContaining("catalog field $field")
        }
        val sameActor = approved.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
            put("checkerId", "maker")
        }
        assertThatThrownBy { lookup(resolver(read = { _, _ -> sameActor })) }
            .hasMessageContaining("independent approval")
        val malformedHash = approved.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
            put("contentHash", "not-a-publication-hash")
        }
        assertThatThrownBy { lookup(resolver(read = { _, _ -> malformedHash })) }
            .hasMessageContaining("publication hash")
        val missingDigest = approved.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
            remove("pensionApprovalDigest")
        }
        assertThatThrownBy { lookup(resolver(read = { _, _ -> missingDigest })) }
            .hasMessageContaining("pensionApprovalDigest")
    }

    @Test
    fun `missing duplicate stale or wrong-role approval evidence fails closed`() {
        listOf(
            emptyList(),
            listOf(approval("LEGAL_COUNSEL")),
            listOf(approval("LEGAL_COUNSEL"), approval("LEGAL_COUNSEL")),
            listOf(approval("LEGAL_COUNSEL"), approval("COMPLIANCE")),
        ).forEach { records ->
            assertThatThrownBy { lookup(resolver(readApprovals = { _, _ -> records })) }
                .hasMessageContaining("role approvals")
        }
        val stale = listOf(approval("LEGAL_COUNSEL"), approval("PRODUCT_OWNER", "c".repeat(64)))
        assertThatThrownBy { lookup(resolver(readApprovals = { _, _ -> stale })) }
            .hasMessageContaining("stale")
        assertThatThrownBy {
            lookup(resolver(readApprovals = { _, _ -> throw WebApplicationException(403) }))
        }.isInstanceOf(WebApplicationException::class.java)
    }

    @Test
    fun `approval evidence must match the requested effective interval`() {
        val wrongInterval = revision(validAttributes).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
            put("effectiveTo", "2026-10-09T09:00:00Z")
        }
        assertThatThrownBy { lookup(resolver(read = { _, _ -> wrongInterval })) }
            .hasMessageContaining("not effective")
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
