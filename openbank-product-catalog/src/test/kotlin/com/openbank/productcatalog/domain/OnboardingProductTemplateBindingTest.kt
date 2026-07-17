// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the phase-1 onboarding-signature invariant (ADR-0170 D5): the CZK onboarding products
 * CURRENT_CZK (id ...c2) and SAVINGS_CZK (id ...c3) must NOT bind a documentTemplateCode.
 *
 * At onboarding the customer signs only the framework agreement (RAMCOVA_SMLOUVA), which
 * incorporates the account-contract terms by reference; a separate account-contract signing
 * ceremony is an explicitly-deferred phase-2 refinement. Binding a template here makes the eager
 * OnboardingDocumentService.issueOnboardingDocument path open a UCET_SMLOUVA ceremony that nothing
 * ever signs — a dangling PENDING artifact (this happened in #1524 and is reverted here). This
 * test fails the build if that binding is re-introduced.
 */
class OnboardingProductTemplateBindingTest {

    @Test
    fun `the CZK onboarding products bind no account-contract template (phase-1, ADR-0170 D5)`() {
        for (code in listOf("CURRENT_CZK", "SAVINGS_CZK")) {
            val product = ProductSeed.products.single { it.code == code }
            val bound = product.termsAndConditions.mapNotNull { it.documentTemplateCode }
            assertThat(bound)
                .describedAs(
                    "%s must NOT bind a documentTemplateCode — onboarding signs only RAMCOVA_SMLOUVA; " +
                        "a per-account-contract ceremony is deferred (ADR-0170 D5)",
                    code,
                )
                .isEmpty()
        }
    }
}
