// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DelegationPreviewOpenApiTest {
    private val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

    @Test
    fun `preview is a documented pre-SCA validation and not an authority decision`() {
        assertThat(contract).contains("/api/v1/delegations/preview:")
        assertThat(contract).contains("PreviewDelegationRequest:")
        assertThat(contract).contains("preview never reads or consumes SCA")
        assertThat(contract).contains("this response is never authorization")
        assertThat(contract).contains("Counterparty names are deliberately not returned")
    }
}
