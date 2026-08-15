// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.balance.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0178 Phase 2 (#1745) — the value-date basis of the spendable figure.
 *
 * The defect these cover: `LedgerProjectionService.apply` books each ledger delta on event receipt
 * and moves `availableAmount` in lock-step, ignoring `entryDate`. A payment received after the
 * 16:00 Prague cut-off (or at a weekend) is booked by `SettlementDateResolver` to the NEXT business
 * day, so the ledger deposit-control — value-dated `entry_date <= today` — does not recognise it
 * until then, while the customer's available balance already carries it and `withReservation` will
 * happily let them spend it.
 *
 * Every amount here is exact `BigDecimal`; the assertions are on the resulting balance value, not
 * on the fact that something ran.
 */
class BalanceValueDateTest {

    private fun balance(
        booked: String,
        available: String,
        notYetEffectiveCredit: String = "0.00",
        overdraft: String = "0.00",
    ) = Balance(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        currency = "CZK",
        bookedAmount = BigDecimal(booked),
        availableAmount = BigDecimal(available),
        reservedAmount = BigDecimal.ZERO,
        pendingAmount = BigDecimal.ZERO,
        updatedAt = OffsetDateTime.parse("2026-08-06T10:00:00Z"),
        version = 0,
        arrangedOverdraftLimit = BigDecimal(overdraft),
        notYetEffectiveCredit = BigDecimal(notYetEffectiveCredit),
    )

    @Test
    fun `a not-yet-effective credit is excluded from the effective booked and available figures`() {
        // 3 000.00 was already effective; 2 000.00 arrived Friday evening booked to Monday.
        val b = balance(booked = "5000.00", available = "5000.00", notYetEffectiveCredit = "2000.00")

        assertThat(b.effectiveBooked()).isEqualByComparingTo(BigDecimal("3000.00"))
        assertThat(b.effectiveAvailable()).isEqualByComparingTo(BigDecimal("3000.00"))
        // The receipt-dated figures are untouched — this adds a basis, it does not restate them.
        assertThat(b.booked()).isEqualByComparingTo(BigDecimal("5000.00"))
        assertThat(b.available()).isEqualByComparingTo(BigDecimal("5000.00"))
    }

    @Test
    fun `on the value date the credit is included, and the balance is exactly the full amount`() {
        // Same account one day later: the tail query (`entry_date > today`) no longer matches it.
        val b = balance(booked = "5000.00", available = "5000.00", notYetEffectiveCredit = "0.00")

        assertThat(b.effectiveBooked()).isEqualByComparingTo(BigDecimal("5000.00"))
        assertThat(b.effectiveAvailable()).isEqualByComparingTo(BigDecimal("5000.00"))
    }

    @Test
    fun `a reservation cannot spend a not-yet-effective credit`() {
        val b = balance(booked = "5000.00", available = "5000.00", notYetEffectiveCredit = "2000.00")

        assertThatThrownBy { b.withReservation(BigDecimal("3000.01")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Insufficient funds")
    }

    @Test
    fun `a reservation up to the effective available figure still succeeds, to the last haler`() {
        val b = balance(booked = "5000.00", available = "5000.00", notYetEffectiveCredit = "2000.00")

        val held = b.withReservation(BigDecimal("3000.00"))

        assertThat(held.availableAmount).isEqualByComparingTo(BigDecimal("2000.00"))
        assertThat(held.reservedAmount).isEqualByComparingTo(BigDecimal("3000.00"))
        // The tail rides along unchanged: nothing about a hold makes the credit effective.
        assertThat(held.effectiveAvailable()).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `the arranged overdraft still applies, measured from the effective figure`() {
        val b = balance(
            booked = "1000.00",
            available = "1000.00",
            notYetEffectiveCredit = "400.00",
            overdraft = "500.00",
        )

        // effectiveAvailable 600.00 + 500.00 overdraft = 1 100.00 spendable, not 1 500.00.
        assertThat(b.effectiveAvailable()).isEqualByComparingTo(BigDecimal("600.00"))
        assertThatThrownBy { b.withReservation(BigDecimal("1100.01")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(b.withReservation(BigDecimal("1100.00")).availableAmount)
            .isEqualByComparingTo(BigDecimal("-100.00"))
    }

    @Test
    fun `an account with nothing future-dated behaves exactly as before`() {
        val b = balance(booked = "750.25", available = "750.25")

        assertThat(b.effectiveAvailable()).isEqualByComparingTo(b.available())
        assertThat(b.effectiveBooked()).isEqualByComparingTo(b.booked())
        assertThat(b.withReservation(BigDecimal("750.25")).availableAmount).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `a negative tail is rejected, so future-dated debits can never add spendable money back`() {
        assertThatThrownBy { balance(booked = "100.00", available = "100.00", notYetEffectiveCredit = "-50.00") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("notYetEffectiveCredit")
    }
}
