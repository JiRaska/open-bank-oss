// SPDX-License-Identifier: MPL-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.\n// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.\n
package com.openbank.balance.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

class BalanceTest {

    @Test
    fun `withReservation moves funds from available to reserved`() {
        val balance = balance()

        val updated = balance.withReservation(BigDecimal("25.00"))

        assertEquals(0, updated.availableAmount.compareTo(BigDecimal("75.00")))
        assertEquals(0, updated.reservedAmount.compareTo(BigDecimal("25.00")))
        assertEquals(1L, updated.version)
    }

    @Test
    fun `applyDebit rejects debit beyond zero with no arranged overdraft`() {
        val ex = assertThrows<IllegalArgumentException> {
            balance(booked = "10.00", available = "10.00").applyDebit(BigDecimal("20.00"))
        }

        assertTrue(ex.message!!.contains("Overdraft limit exceeded"))
    }

    @Test
    fun `applyDebit allows drawing into an arranged overdraft`() {
        val updated = balance(booked = "100.00", available = "100.00", overdraft = "500.00")
            .applyDebit(BigDecimal("400.00"))

        assertEquals(0, updated.bookedAmount.compareTo(BigDecimal("-300.00")))
        assertTrue(updated.isOverdrawn())
        assertEquals(0, updated.overdraftUsed().compareTo(BigDecimal("300.00")))
    }

    @Test
    fun `applyDebit allows drawing down to exactly the arranged limit`() {
        val updated = balance(booked = "0.00", available = "0.00", overdraft = "500.00")
            .applyDebit(BigDecimal("500.00"))

        assertEquals(0, updated.bookedAmount.compareTo(BigDecimal("-500.00")))
    }

    @Test
    fun `applyDebit rejects an unarranged overdraft beyond the limit`() {
        val ex = assertThrows<IllegalArgumentException> {
            balance(booked = "0.00", available = "0.00", overdraft = "500.00").applyDebit(BigDecimal("500.01"))
        }

        assertTrue(ex.message!!.contains("Overdraft limit exceeded"))
    }

    @Test
    fun `withReservation may use the arranged overdraft`() {
        val updated = balance(booked = "100.00", available = "100.00", overdraft = "200.00")
            .withReservation(BigDecimal("250.00"))

        assertEquals(0, updated.availableAmount.compareTo(BigDecimal("-150.00")))
        assertEquals(0, updated.reservedAmount.compareTo(BigDecimal("250.00")))
    }

    @Test
    fun `withReservation rejects reservation beyond the arranged overdraft`() {
        val ex = assertThrows<IllegalArgumentException> {
            balance(booked = "100.00", available = "100.00", overdraft = "200.00").withReservation(BigDecimal("301.00"))
        }

        assertTrue(ex.message!!.contains("Insufficient funds"))
    }

    @Test
    fun `applyBookedDelta adds a positive delta to booked and available`() {
        val updated = balance(booked = "100.00", available = "60.00").applyBookedDelta(BigDecimal("40.00"))

        assertEquals(0, updated.bookedAmount.compareTo(BigDecimal("140.00")))
        assertEquals(0, updated.availableAmount.compareTo(BigDecimal("100.00")))
        assertEquals(1L, updated.version)
    }

    @Test
    fun `applyBookedDelta subtracts a negative delta from booked and available`() {
        val updated = balance(booked = "100.00", available = "60.00").applyBookedDelta(BigDecimal("-40.00"))

        assertEquals(0, updated.bookedAmount.compareTo(BigDecimal("60.00")))
        assertEquals(0, updated.availableAmount.compareTo(BigDecimal("20.00")))
    }

    @Test
    fun `applyBookedDelta is not refused by the overdraft guard - a posted fact always lands`() {
        // No arranged overdraft, yet a posted debit larger than the balance must still apply: the
        // projection reflects the ledger, it does not re-decide whether the spend was allowed.
        val updated = balance(booked = "10.00", available = "10.00").applyBookedDelta(BigDecimal("-50.00"))

        assertEquals(0, updated.bookedAmount.compareTo(BigDecimal("-40.00")))
        assertTrue(updated.isOverdrawn())
    }

    @Test
    fun `overdraftUsed is zero for a positive balance`() {
        assertEquals(0, balance(booked = "50.00").overdraftUsed().compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `rejects a negative arranged overdraft limit`() {
        val ex = assertThrows<IllegalArgumentException> {
            balance(overdraft = "-1.00")
        }

        assertTrue(ex.message!!.contains("arrangedOverdraftLimit must be non-negative"))
    }

    @Test
    fun `releaseReservation returns reserved funds to available`() {
        val b = balance(booked = "100.00", available = "70.00").copy(reservedAmount = BigDecimal("30.00"))

        val updated = b.releaseReservation(BigDecimal("30.00"))

        assertEquals(0, updated.availableAmount.compareTo(BigDecimal("100.00")))
        assertEquals(0, updated.reservedAmount.compareTo(BigDecimal.ZERO))
        assertEquals(1L, updated.version)
    }

    @Test
    fun `releaseReservation caps at reservedAmount when request exceeds what is held`() {
        val b = balance(booked = "100.00", available = "80.00").copy(reservedAmount = BigDecimal("20.00"))

        // Request to release 50 but only 20 is reserved — release is capped to 20.
        val updated = b.releaseReservation(BigDecimal("50.00"))

        assertEquals(0, updated.availableAmount.compareTo(BigDecimal("100.00")))
        assertEquals(0, updated.reservedAmount.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `applyCredit increments both booked and available by the given amount`() {
        val updated = balance(booked = "100.00", available = "100.00").applyCredit(BigDecimal("50.00"))

        assertEquals(0, updated.bookedAmount.compareTo(BigDecimal("150.00")))
        assertEquals(0, updated.availableAmount.compareTo(BigDecimal("150.00")))
        assertEquals(1L, updated.version)
    }

    @Test
    fun `applyCredit on a negative booked balance reduces overdraft used`() {
        val updated = balance(booked = "-200.00", available = "-200.00", overdraft = "500.00")
            .applyCredit(BigDecimal("100.00"))

        assertEquals(0, updated.bookedAmount.compareTo(BigDecimal("-100.00")))
        assertTrue(updated.isOverdrawn())
        assertEquals(0, updated.overdraftUsed().compareTo(BigDecimal("100.00")))
    }

    @Test
    fun `isOverdrawn returns false for a positive balance`() {
        assertFalse(balance(booked = "1.00").isOverdrawn())
    }

    @Test
    fun `available booked reserved return their respective fields`() {
        val b = balance(booked = "200.00", available = "150.00").copy(reservedAmount = BigDecimal("50.00"))
        assertEquals(0, b.booked().compareTo(BigDecimal("200.00")))
        assertEquals(0, b.available().compareTo(BigDecimal("150.00")))
        assertEquals(0, b.reserved().compareTo(BigDecimal("50.00")))
    }

    private fun balance(booked: String = "100.00", available: String = "100.00", overdraft: String = "0.00") = Balance(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        currency = "CZK",
        bookedAmount = BigDecimal(booked),
        availableAmount = BigDecimal(available),
        reservedAmount = BigDecimal.ZERO,
        pendingAmount = BigDecimal.ZERO,
        updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        version = 0L,
        arrangedOverdraftLimit = BigDecimal(overdraft),
    )
}
