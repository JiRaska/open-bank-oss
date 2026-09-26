// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CustomerDelegationRecertificationOpenApiTest {
    private val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

    @Test
    fun `customer delegation routes document the explicit review cadence without claiming new rights`() {
        assertThat(contract).contains("recertificationAudience:")
        assertThat(contract).contains("enum: [PERSONAL, FOP, SME, CORPORATE]")
        assertThat(contract).contains("never changes access rights")
        assertThat(contract).contains("company size is not inferred")
        assertThat(contract).contains("/delegations/recertifications:")
        assertThat(contract).contains("/delegations/recertifications/{id}/confirm:")
        assertThat(contract).contains("DelegationRecertification:")
        assertThat(contract).contains("Review recorded; access has not changed")
    }

    @Test
    fun `a customer without the review authority is forbidden`() {
        val reviewRoutes = contract.substringAfter("/delegations/recertifications:")
            .substringBefore("/profiles:")

        assertThat(reviewRoutes).contains("'403'")
    }
}
