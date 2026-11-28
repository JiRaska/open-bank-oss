// SPDX-License-Identifier: Apache-2.0
package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.productcatalog.domain.catalog.PriceComponent
import com.openbank.productcatalog.domain.catalog.SchemaRef
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Checked-in drafts. Publication and effective terms remain owned by the generic catalog. */
@ApplicationScoped
class DeclarativeDeposits(
    mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.catalog.deposit-definitions", defaultValue = "/catalog-seeds/banking/deposits.json")
    resource: String,
) {
    val definitions: List<Definition> = requireNotNull(javaClass.getResourceAsStream(resource)) {
        "deposit definitions resource is missing: $resource"
    }.use(mapper::readTree).let { document ->
        require(document.isArray) { "deposit definitions must be an array" }
        document.map { mapper.treeToValue(it, Definition::class.java) }.also { rows ->
            require(rows.map { it.code }.distinct().size == rows.size) { "duplicate declarative deposit code" }
        }
    }

    data class Definition(
        val code: String,
        val name: String,
        val attributes: JsonNode,
        val prices: List<PriceComponent>,
        val effectiveDate: LocalDate,
    ) {
        val productId: UUID
            get() = identity(code)
        val effectiveFrom: Instant
            get() = effectiveDate.atStartOfDay().toInstant(ZoneOffset.UTC)

        init {
            require(attributes.path("productType").asText() == "SAVINGS") { "only savings deposits are supported" }
            require(attributes.path("interest").path("rateType").asText() == "FIXED") {
                "only fixed interest is supported for declarative deposits"
            }
        }
    }

    companion object {
        val SCHEMA = SchemaRef("org.openbank.banking.deposit", 3)

        fun identity(code: String): UUID =
            UUID.nameUUIDFromBytes("declarative-deposit:$code".toByteArray(StandardCharsets.UTF_8))
    }
}
