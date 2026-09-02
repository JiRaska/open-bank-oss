// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CustomerDelegationPreviewOpenApiTest {
    private val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
    private val normalized = contract.replace(Regex("\\s+"), " ")

    @Test
    fun `customer contract exposes preview before SCA without making an authorization promise`() {
        assertThat(contract).contains("/delegations/preview:")
        assertThat(contract).contains("before the app starts SCA")
        assertThat(normalized).contains("creates no grant, emits no event and never consumes SCA")
        assertThat(normalized).contains("a successful preview is not an authorization decision")
    }
}
