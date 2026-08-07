// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MerchantCategoryTaxonomyTest {
    @Test
    fun `a single code and a range both classify`() {
        assertThat(MerchantCategoryTaxonomy.categoryOf("7995")).isEqualTo("GAMBLING")
        assertThat(MerchantCategoryTaxonomy.categoryOf("7801")).isEqualTo("GAMBLING")
        assertThat(MerchantCategoryTaxonomy.categoryOf("5411")).isEqualTo("GROCERIES")
    }

    @Test
    fun `range ends are inclusive`() {
        assertThat(MerchantCategoryTaxonomy.categoryOf("7800")).isEqualTo("GAMBLING")
        assertThat(MerchantCategoryTaxonomy.categoryOf("7802")).isEqualTo("GAMBLING")
        // Just outside must not be swept in — an off-by-one here blocks or frees real spend.
        assertThat(MerchantCategoryTaxonomy.categoryOf("7799")).isNotEqualTo("GAMBLING")
        assertThat(MerchantCategoryTaxonomy.categoryOf("7803")).isNotEqualTo("GAMBLING")
    }

    @Test
    fun `an unknown or malformed code lands in OTHER instead of throwing`() {
        // An authorisation must never fail because an acquirer sent something unexpected.
        assertThat(MerchantCategoryTaxonomy.categoryOf("9999")).isEqualTo(MerchantCategoryTaxonomy.UNMAPPED)
        assertThat(MerchantCategoryTaxonomy.categoryOf("abcd")).isEqualTo(MerchantCategoryTaxonomy.UNMAPPED)
        assertThat(MerchantCategoryTaxonomy.categoryOf("")).isEqualTo(MerchantCategoryTaxonomy.UNMAPPED)
        assertThat(MerchantCategoryTaxonomy.categoryOf(null)).isEqualTo(MerchantCategoryTaxonomy.UNMAPPED)
    }

    @Test
    fun `OTHER is limitable but never blockable`() {
        assertThat(MerchantCategoryTaxonomy.isBlockable(MerchantCategoryTaxonomy.UNMAPPED)).isFalse()
        assertThat(MerchantCategoryTaxonomy.CATEGORIES.single { it.id == MerchantCategoryTaxonomy.UNMAPPED }.limitable)
            .isTrue()
    }

    @Test
    fun `gambling is blockable — the reason the feature exists`() {
        assertThat(MerchantCategoryTaxonomy.isBlockable("GAMBLING")).isTrue()
    }

    @Test
    fun `an unknown category id is not treated as blockable`() {
        assertThat(MerchantCategoryTaxonomy.isBlockable("NOT_A_CATEGORY")).isFalse()
        assertThat(MerchantCategoryTaxonomy.isKnown("NOT_A_CATEGORY")).isFalse()
    }

    @Test
    fun `every published range parses and is well formed`() {
        // The ranges are strings because that is what the API serves; a typo would otherwise only
        // surface as a silently mis-classified payment.
        for (c in MerchantCategoryTaxonomy.CATEGORIES) {
            for (spec in c.mccRanges) {
                val parts = spec.split("-")
                assertThat(parts.size).describedAs("range %s in %s", spec, c.id).isBetween(1, 2)
                val from = parts.first().toIntOrNull()
                val to = parts.last().toIntOrNull()
                assertThat(from).describedAs("range %s in %s", spec, c.id).isNotNull()
                assertThat(to).describedAs("range %s in %s", spec, c.id).isNotNull()
                assertThat(from!!).describedAs("range %s in %s", spec, c.id).isLessThanOrEqualTo(to!!)
            }
        }
    }

    @Test
    fun `category ids are unique`() {
        val ids = MerchantCategoryTaxonomy.CATEGORIES.map { it.id }
        assertThat(ids).doesNotHaveDuplicates()
    }
}
