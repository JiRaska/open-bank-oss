// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DelegationRecertificationOpenApiTest {
    private val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

    @Test
    fun `review audience is documented as an explicit non-authorisation cadence choice`() {
        assertThat(contract).contains("recertificationAudience:")
        assertThat(contract).contains("enum: [PERSONAL, FOP, SME, CORPORATE]")
        assertThat(contract).contains("never changes a")
        assertThat(contract).contains("service does not infer company size")
    }

    @Test
    fun `a caller missing the required review identity is rejected`() {
        val confirmOperation = contract.substringAfter("/api/v1/delegations/recertifications/{id}/confirm:")
            .substringBefore("/api/v1/delegations/{id}:")

        assertThat(confirmOperation).contains("'403'")
    }
}
