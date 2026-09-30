// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [BalanceDto] must bind the body balance-service actually sends. It used to declare a non-null
 * `currentBalance` that no balance-service response has ever carried, so every read failed to
 * construct and the adapter's `runCatching` reported "no balance" — silently, on every fee run (#11650).
 */
class BalanceDtoBindingTest {

    private val mapper = jacksonObjectMapper()

    /** The wire shape of `GET /api/v1/balances/{accountId}/{currency}`, as its consumer pacts record it. */
    private val balanceServiceBody = """
        {"accountId":"d5d5d5d5-d5d5-d5d5-d5d5-d5d5d5d5d5d5","currency":"CZK","bookedAmount":5000.00,
         "availableAmount":4900.00,"reservedAmount":100.00,"pendingAmount":0,"arrangedOverdraftLimit":0,
         "updatedAt":"2026-01-15T10:00:00Z","version":0}
    """.trimIndent()

    @Test
    fun `the balance-service body binds and carries the booked amount`() {
        val dto = mapper.readValue<BalanceDto>(balanceServiceBody)
        assertThat(dto.bookedAmount).isEqualByComparingTo(BigDecimal("5000.00"))
        assertThat(dto.currency).isEqualTo("CZK")
    }
}
