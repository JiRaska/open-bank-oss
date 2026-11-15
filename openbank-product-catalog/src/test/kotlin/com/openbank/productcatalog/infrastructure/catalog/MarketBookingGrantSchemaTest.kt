// SPDX-License-Identifier: Apache-2.0
package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.productcatalog.domain.catalog.CatalogSchema
import com.openbank.productcatalog.domain.catalog.SchemaRef
import com.openbank.productcatalog.domain.catalog.SchemaValidationResult
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

class MarketBookingGrantSchemaTest {
    private val mapper = ObjectMapper()
    private val json = CatalogJson(mapper)
    private val validator = JsonSchemaCatalogValidator(json, CatalogSchemaProfile())
    private val document = requireNotNull(
        javaClass.getResourceAsStream("/catalog-packs/market-booking/booking-grant-v1.schema.json"),
    ).use { mapper.readTree(it) }
    private val schema = CatalogSchema(
        SchemaRef("org.openbank.market.booking-grant", 1),
        json.toObject(document),
        json.sha256(document),
        Instant.EPOCH,
    )

    @Test
    fun `the approved route carries exactly the facts needed for its mode`() {
        val domestic = grant("DOMESTIC")
        val fos = grant("FOS").put("hostCountry", "AT")
        val branch = grant("BRANCH").put("hostCountry", "AT").put("establishmentId", ESTABLISHMENT)

        listOf(domestic, fos, branch).forEach {
            assertInstanceOf(SchemaValidationResult.Valid::class.java, validator.validate(schema, json.toObject(it)))
        }

        listOf(
            grant("BRANCH").put("hostCountry", "AT"),
            grant("FOS").put("hostCountry", "AT").put("establishmentId", ESTABLISHMENT),
            grant("FOS"),
            grant("DOMESTIC").put("hostCountry", "AT"),
            grant("DOMESTIC").put("legalEntityId", "unverified-company"),
        ).forEach {
            assertInstanceOf(SchemaValidationResult.Invalid::class.java, validator.validate(schema, json.toObject(it)))
        }
    }

    private fun grant(mode: String) = mapper.createObjectNode()
        .put("legalEntityId", "00000000-0000-4000-8000-000000000001")
        .put("bookingBookId", "00000000-0000-4000-8000-000000000002")
        .put("productRevisionId", "00000000-0000-4000-8000-000000000003")
        .put("homeCountry", "CZ")
        .put("mode", mode)
        .put("approvalEvidenceHash", "a".repeat(64))

    private companion object {
        const val ESTABLISHMENT = "00000000-0000-4000-8000-000000000004"
    }
}
