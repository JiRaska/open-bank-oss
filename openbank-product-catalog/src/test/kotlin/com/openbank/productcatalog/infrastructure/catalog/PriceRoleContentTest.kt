// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.productcatalog.domain.catalog.CatalogValue
import com.openbank.productcatalog.domain.catalog.LocalizedText
import com.openbank.productcatalog.domain.catalog.PriceCadence
import com.openbank.productcatalog.domain.catalog.PriceComponent
import com.openbank.productcatalog.domain.catalog.PriceKind
import com.openbank.productcatalog.domain.catalog.PriceRole
import com.openbank.productcatalog.domain.catalog.RevisionContent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * #13230: the price role is additive. A component without one serialises to EXACTLY the key set it had
 * before roles existed, so every published revision keeps its content hash and approval digest; a
 * component with one round-trips it.
 */
class PriceRoleContentTest {
    private val mapper = ObjectMapper().findAndRegisterModules()
    private val json = CatalogJson(mapper)

    private fun content(role: PriceRole?) = RevisionContent(
        name = LocalizedText(mapOf("en" to "Account")),
        attributes = CatalogValue.ObjectValue(emptyMap()),
        prices = listOf(
            PriceComponent(
                code = "MAINTENANCE",
                kind = PriceKind.AMOUNT,
                value = BigDecimal("49.00"),
                currency = "CZK",
                unit = "month",
                cadence = PriceCadence.MONTHLY,
                role = role,
            ),
        ),
    )

    @Test
    fun `a component without a role serialises exactly as before roles existed`() {
        val price = json.toContentNode(content(null)).withArray("prices")[0]
        assertThat(price.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "code", "kind", "value", "currency", "unit", "cadence", "taxTreatment", "effectiveFrom", "effectiveTo",
        )
    }

    @Test
    fun `a role is serialised, changes the content, and round-trips`() {
        val withRole = json.toContentNode(content(PriceRole.FEE))
        assertThat(withRole.withArray("prices")[0]["role"].asText()).isEqualTo("FEE")
        assertThat(withRole).isNotEqualTo(json.toContentNode(content(null)))
        assertThat(json.toContent(withRole).prices.single().role).isEqualTo(PriceRole.FEE)
        assertThat(json.toContent(json.toContentNode(content(null))).prices.single().role).isNull()
    }

    @Test
    fun `a FEE must be an AMOUNT, while a LIMIT or RATE may be either kind`() {
        assertThatThrownBy {
            PriceComponent(
                "INTEREST",
                PriceKind.RATE,
                BigDecimal("0.02"),
                null,
                "rate",
                PriceCadence.ANNUALLY,
                role = PriceRole.FEE,
            )
        }.hasMessageContaining("FEE price component must be an AMOUNT")
        PriceComponent(
            "LIMIT",
            PriceKind.AMOUNT,
            BigDecimal("1000"),
            "CZK",
            "day",
            PriceCadence.DAILY,
            role = PriceRole.LIMIT,
        )
        PriceComponent(
            "RATE",
            PriceKind.RATE,
            BigDecimal("0.02"),
            null,
            "rate",
            PriceCadence.ANNUALLY,
            role = PriceRole.RATE,
        )
    }
}
