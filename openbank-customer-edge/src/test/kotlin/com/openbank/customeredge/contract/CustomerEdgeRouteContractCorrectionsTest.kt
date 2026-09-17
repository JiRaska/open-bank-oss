// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CustomerEdgeRouteContractCorrectionsTest {
    private val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

    @Test
    fun `pocket routing and exchange paths publish their exact request and quote shapes`() {
        assertThat(contract).contains(
            "/accounts/{accountId}/pockets/resolve:",
            "/accounts/{accountId}/pockets/{currency}/convert/quote:",
            "/accounts/{accountId}/pockets/{currency}/convert:",
            "/accounts/{accountId}/pockets/{fromCurrency}/exchange:",
            "PocketResolution:",
            "PocketFxQuote:",
        )
        val exchange = contract.substringAfter("  /accounts/{accountId}/pockets/{fromCurrency}/exchange:")
            .substringBefore("  /accounts/{accountId}/pockets/{currency}:")
        assertThat(exchange).contains("required: [toCurrency, amount]", "Idempotency-Key")
    }

    @Test
    fun `device session routes remain publicly callable but require an opaque session credential`() {
        val refresh = contract.substringAfter("  /webauthn/session/refresh:")
            .substringBefore("  /webauthn/session/revoke:")
        val revoke = contract.substringAfter("  /webauthn/session/revoke:")
            .substringBefore("  # --- Cards (lifecycle)")
        assertThat(refresh).contains("security: []", "DeviceSessionRequest", "TokenPair")
        assertThat(revoke).contains("security: []", "DeviceSessionRequest", "'204'")
        val deviceSession = contract.substringAfter("    DeviceSessionRequest:")
            .substringBefore("    PocketResolution:")
        assertThat(deviceSession).contains("required: [device_session_id]")
    }
}
