// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DelegationAcceptanceOpenApiTest {
    @Test
    fun `accept contract carries the customer actor separately from the company grantee`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        val accept = contract.substringAfter("  /api/v1/delegations/{id}/accept:")
            .substringBefore("  /api/v1/delegations/{id}/decline:")

        assertThat(accept).contains("#/components/parameters/CustomerPartyId")
        assertThat(accept).contains("#/components/parameters/CustomerActorPartyId")
        assertThat(accept).contains("actor lacks sole authority")
        assertThat(accept).contains(
            "'403': { description: The authenticated party is not the grantee, or its actor lacks sole authority }",
        )
    }
}
