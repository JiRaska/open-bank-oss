// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class CatalogKernelTest {
    @Test
    fun `industry-neutral item requires no currency or banking fields`() {
        val at = Instant.parse("2026-08-12T09:00:00Z")
        val item = ProductRevision(
            offeringId = java.util.UUID.randomUUID(),
            number = 1,
            schemaRef = SchemaRef("org.openbank.insurance.term-life", 1),
            content = RevisionContent(
                name = LocalizedText(mapOf("en" to "Reference product")),
                attributes = CatalogValue.ObjectValue(
                    mapOf(
                        "coverageAmount" to CatalogValue.DecimalValue(BigDecimal("100000.00")),
                        "smoker" to CatalogValue.BooleanValue(false),
                    ),
                ),
            ),
            makerId = "maker",
            createdAt = at,
            updatedAt = at,
        )

        assertThat(item.schemaRef).isEqualTo(SchemaRef("org.openbank.insurance.term-life", 1))
        assertThat(item.content.attributes.values).doesNotContainKeys("currency", "cardConfig", "baseRate")
    }

    @Test
    fun `decimal catalog values preserve exact money precision`() {
        val exact = BigDecimal("1000000000000000000000000.10")
        val value = CatalogValue.DecimalValue(exact)

        assertThat(value.value.toPlainString()).isEqualTo("1000000000000000000000000.10")
    }

    @Test
    fun `effective interval must be strictly increasing`() {
        val at = Instant.parse("2026-08-12T10:00:00Z")

        assertThatThrownBy {
            ProductRevision(
                offeringId = java.util.UUID.randomUUID(),
                number = 1,
                schemaRef = SchemaRef("org.openbank.banking.deposit", 1),
                content = RevisionContent(
                    name = LocalizedText(mapOf("en" to "Reference")),
                    attributes = CatalogValue.ObjectValue(emptyMap()),
                ),
                effectiveFrom = at,
                effectiveTo = at,
                makerId = "maker",
                createdAt = at,
                updatedAt = at,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("effectiveTo must be after effectiveFrom")
    }

    @Test
    fun `schema ref is namespaced and exact`() {
        assertThatThrownBy { SchemaRef("insurance", 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SchemaRef("org.openbank.insurance", 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
