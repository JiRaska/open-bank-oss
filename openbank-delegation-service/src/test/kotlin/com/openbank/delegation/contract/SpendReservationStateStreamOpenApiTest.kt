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
    }

    @Test
    fun `contract pins the negative case — a reservation on someone else's grant is 404`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

        // ADR-0279 #3: enumeration resistance is part of the wire contract — a caller holding
        // no grant on the delegation gets the same 404 as a nonexistent one, never a 403 that
        // would confirm the delegation exists.
        assertThat(contract).contains("'404': { description: No such delegation, or the caller is not its grantee }")
    }
}
