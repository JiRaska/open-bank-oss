// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpendReservationStateStreamOpenApiTest {
    @Test
    fun `contract exposes opt-in rail and fail-closed admission outcomes`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

        assertThat(contract).contains("operationType:")
        assertThat(contract).contains("enum: [UNSPECIFIED, DOMESTIC_PAYMENT]")
        assertThat(contract).contains("IDEMPOTENCY_KEY_REUSED")
        assertThat(contract).contains("Domestic reservation admission is disabled")
        assertThat(contract).contains("version: 1.9.0")
    }
}
