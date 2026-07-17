// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the onboarding products' account-contract binding. CURRENT_CZK (id ...c2) and SAVINGS_CZK
 * (id ...c3) are what account-service opens for every self-service onboarding
 * (openbank.account.onboarding.default-product-id / savings-product-id). document-service's
 * eager OnboardingDocumentService.issueOnboardingDocument resolves the account-contract template
 * off the product's documentTemplateCode; when it was absent the whole eager path silently
 * no-op'd for every new customer (no account-contract document, only an INFO log). This test
 * makes that omission a failing build, not a live discovery.
 */
class OnboardingProductTemplateBindingTest {

    @Test
    fun `the CZK onboarding products bind an account-contract document template`() {
        for (code in listOf("CURRENT_CZK", "SAVINGS_CZK")) {
            val product = ProductSeed.products.single { it.code == code }
            val bound = product.termsAndConditions.mapNotNull { it.documentTemplateCode }
            assertThat(bound)
                .describedAs("%s must bind a documentTemplateCode so onboarding can issue its contract", code)
                .contains("UCET_SMLOUVA_CS")
        }
    }
}
