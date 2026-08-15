// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * #1745 reaches account-service, or it reaches nobody.
 *
 * balance-service subtracts the not-yet-effective credit tail before granting a hold, so its own
 * cover decisions were correct as soon as that landed. Every caller behind account-service was not:
 * `toBalanceView()` mapped `available` straight from `availableAmount`, so the correction sat in the
 * payload as a field nothing read, and the invariant looked fixed while the defect stayed live
 * downstream.
 *
 * These assert the mapping, not the presence of a field — a `containsKey`-shaped test would pass
 * against the old code, which also carried the field once balance-service started sending it.
 */
class BalanceViewSpendableTest {
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    private fun dto(json: String): BalanceDto = mapper.readValue(json, BalanceDto::class.java)

    private val base = """
        "accountId":"11111111-1111-1111-1111-111111111111",
        "currency":"CZK",
        "bookedAmount":5000.00,
        "availableAmount":5000.00,
        "reservedAmount":0.00,
        "pendingAmount":0.00,
        "updatedAt":"2026-08-09T10:00:00Z"
    """.trimIndent()

    @Test
    fun `a not-yet-effective credit is not reported as spendable`() {
        // The concrete #1745 shape: 5000 booked, 2000 of it value-dated tomorrow.
        val view = dto("""{$base,"effectiveAvailableAmount":3000.00}""").toBalanceView()

        assertThat(view.available)
            .describedAs("available must be the spendable figure, not the raw projection")
            .isEqualByComparingTo(BigDecimal("3000.00"))
    }

    @Test
    fun `the raw projection is still reported as booked`() {
        // availableAmount keeps its meaning — this is the half that must NOT move, or reconciliation
        // against the projection breaks.
        val view = dto("""{$base,"effectiveAvailableAmount":3000.00}""").toBalanceView()

        assertThat(view.booked).isEqualByComparingTo(BigDecimal("5000.00"))
    }

    @Test
    fun `an older balance-service that omits the field falls back to availableAmount`() {
        // During a rollout this service can be talking to a provider that predates the field.
        // Falling back is wrong in the direction it was ALREADY wrong; defaulting to zero would be
        // newly wrong in the other, reporting every account as unspendable.
        val view = dto("""{$base}""").toBalanceView()

        assertThat(view.available).isEqualByComparingTo(BigDecimal("5000.00"))
    }
}
