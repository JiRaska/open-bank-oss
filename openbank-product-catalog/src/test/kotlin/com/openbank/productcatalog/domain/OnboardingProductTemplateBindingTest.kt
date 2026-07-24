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
 *
 * The named-product check below covered only CURRENT_CZK/SAVINGS_CZK, so the same binding survived on
 * CURRENT_PERSONAL — a catalog-display product outside the guard — and produced the dangling,
 * later-manually-archived UCET_SMLOUVA_CS rows investigated in #1840. The fleet-wide check pins the
 * invariant for EVERY seeded product so no future product can reintroduce it unnoticed.
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

    @Test
    fun `no seeded product binds a UCET account-contract template (fleet-wide, ADR-0170 D5, issue #1840)`() {
        val offenders = ProductSeed.products.flatMap { p ->
            p.termsAndConditions
                .mapNotNull { it.documentTemplateCode }
                .filter { it.startsWith("UCET_") }
                .map { "${p.code} -> $it" }
        }
        assertThat(offenders)
            .describedAs(
                "no product may bind a UCET_* account-contract template — the eager " +
                    "OnboardingDocumentService.issueOnboardingDocument path opens a signing ceremony " +
                    "nothing ever signs (a dangling artifact, #1840). The account contract is " +
                    "incorporated into RAMCOVA_SMLOUVA and signed once; a separate account-contract " +
                    "ceremony is a deferred phase-2 refinement (ADR-0170 D5).",
            )
            .isEmpty()
    }
}
