// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

import com.openbank.libs.spend.SpendCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Binds this service's category ids to the bank-wide vocabulary in `openbank-libs-domain`.
 *
 * transaction-service now lets a customer put one of those ids on their own transaction, and
 * validates the input against [SpendCategory.IDS]. If this service quietly renames, drops or
 * reorders a category, the two halves stop speaking the same language: a stored override would
 * name a category card controls no longer know, or a category customers can never choose.
 *
 * This test is the only thing preventing that, so it compares the whole sequence — not sizes, not
 * set membership — and fails on a reordering too.
 */
class MerchantCategoryTaxonomyDriftTest {
    @Test
    fun `taxonomy ids are exactly the shared spend vocabulary, in the same order`() {
        assertThat(MerchantCategoryTaxonomy.CATEGORIES.map { it.id })
            .containsExactlyElementsOf(SpendCategory.IDS)
    }

    @Test
    fun `the unmapped id is the same string in both places`() {
        assertThat(MerchantCategoryTaxonomy.UNMAPPED).isEqualTo(SpendCategory.UNMAPPED)
    }
}
