// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The business agreement journey as the app sees it: routes, shared schema names and refusals. */
class CustomerBusinessAgreementOpenApiTest {
    private val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
    private val normalized = contract.replace(Regex("\\s+"), " ")

    @Test
    fun `the five onboarding routes are published with the shared schema names`() {
        assertThat(normalized).contains("/business/onboarding/{id}/questionnaire: put:")
        assertThat(normalized).contains("/business/onboarding/{id}/questionnaire/prefill: get:")
        assertThat(normalized).contains("/business/onboarding/{id}/declarations: put:")
        assertThat(normalized).contains("/business/onboarding/{id}/agreement: post:")
        assertThat(normalized).contains("/business/onboarding/{id}/agreement/accept: post:")
        listOf(
            "BusinessQuestionnaire",
            "BusinessQuestionnairePrefill",
            "BusinessDeclarations",
            "BusinessAgreement",
            "DisclosureAcceptance",
        )
            .forEach { assertThat(contract).contains("  $it:\n").contains("#/components/schemas/$it'") }
        assertThat(normalized).contains("name: lang in: query required: false schema: { type: string, enum: [cs, en] }")
    }

    @Test
    fun `refusals are documented - stale acceptance 409, not involved 404, missing mandate 403`() {
        assertThat(normalized).contains("'409': {description: The accepted set does not match the current disclosures")
        assertThat(normalized).contains("'404': {description: Not involved}")
        assertThat(normalized).contains("Business partyId to act for; 403 without an active mandate")
        assertThat(normalized).contains("partyRef is forced to the JWT's human party, also under X-Acting-For")
    }
}
