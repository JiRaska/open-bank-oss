// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.productcatalog.domain.catalog.ProductSpecification
import com.openbank.productcatalog.domain.catalog.SchemaRef
import com.openbank.productcatalog.infrastructure.persistence.BankV1CatalogMapping
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DeclaredOfferingIdentityTest {
    @Test
    fun `deposit schema and code do not establish legacy identity for an arbitrary product ID`() {
        val seeded = ProductSpecification(
            id = UUID.fromString("e1237e4f-b065-3bf3-a579-d3f98893a9f2"),
            code = "DECLARATIVE_SAVINGS_TEST",
            schemaRef = SchemaRef("org.openbank.banking.deposit", 3),
            createdAt = Instant.EPOCH,
        )
        val ordinary = seeded.copy(id = UUID.fromString("f1237e4f-b065-4bf3-a579-d3f98893a9f2"))

        assertThat(BankV1CatalogMapping.DeclaredOffering.matchesDeclaredSpecification(seeded)).isTrue()
        assertThat(BankV1CatalogMapping.DeclaredOffering.matchesDeclaredSpecification(ordinary)).isFalse()
    }
}
