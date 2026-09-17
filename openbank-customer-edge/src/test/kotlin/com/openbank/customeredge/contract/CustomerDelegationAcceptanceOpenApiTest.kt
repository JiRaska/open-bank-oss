// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CustomerDelegationAcceptanceOpenApiTest {
    @Test
    fun `selected company acceptance publishes exact offer intent ballot progress and activation`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        val section = contract.substringAfter("  /delegations/statutory-acceptances/for-grant/{grantId}:")
            .substringBefore("  /delegations/recertifications:")

        assertThat(section).contains(
            "statutory-acceptances/{id}:",
            "statutory-acceptances/{id}/approval-intent:",
            "statutory-acceptances/{id}/decisions:",
            "statutory-acceptances/{id}/progress:",
            "statutory-acceptances/{id}/execute:",
            "StatutoryDelegationAcceptance",
            "StatutoryApprovalIntent",
        )
        assertThat(contract).contains("DELEGATION_STATUTORY_ACCEPTANCE")
    }

    @Test
    fun `acceptance inbox is explicitly published to selected company`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        val route = contract.substringAfter("  /delegations/statutory-acceptances/pages:")
            .substringBefore("  /delegations/statutory-acceptances/for-grant/{grantId}:")
        assertThat(route).contains("pageStatutoryAcceptances", "maximum: 50", "StatutoryDelegationAcceptancePage")
    }
}
