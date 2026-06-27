// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.domain.reconciliation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Pure unit tests for the ADR-0039 Phase A control-account ⇄ sub-ledger tie-out. No framework, no I/O.
 */
class ReconciliationPolicyTest {

    private val asOf = LocalDate.of(2026, 1, 31)
    private val now = OffsetDateTime.parse("2026-02-01T00:00:00Z")

    private fun reconcile(
        ledger: Map<String, BigDecimal>,
        booked: Map<String, BigDecimal>,
        tolerance: BigDecimal = BigDecimal.ZERO,
    ) = ReconciliationPolicy.reconcile(ledger, booked, asOf, now, tolerance)

    @Test
    fun `matching balances net to zero and report no drift`() {
        val report = reconcile(
            ledger = mapOf("CZK" to BigDecimal("1000.00"), "EUR" to BigDecimal("50.00")),
            booked = mapOf("CZK" to BigDecimal("1000.00"), "EUR" to BigDecimal("50.00")),
        )

        assertFalse(report.hasDrift)
        assertTrue(report.driftedCurrencies.isEmpty())
        assertEquals(2, report.currencies.size)
        report.currencies.forEach { assertEquals(0, it.difference.compareTo(BigDecimal.ZERO)) }
    }

    @Test
    fun `a mismatch in one currency is flagged as drift with the signed difference`() {
        val report = reconcile(
            ledger = mapOf("CZK" to BigDecimal("1000.00"), "EUR" to BigDecimal("50.00")),
            booked = mapOf("CZK" to BigDecimal("1000.00"), "EUR" to BigDecimal("47.50")),
        )

        assertTrue(report.hasDrift)
        assertEquals(listOf("EUR"), report.driftedCurrencies)
        val eur = report.currencies.single { it.currency == "EUR" }
        assertFalse(eur.withinTolerance)
        // subLedger − ledger = 47.50 − 50.00 = -2.50
        assertEquals(0, eur.difference.compareTo(BigDecimal("-2.50")))
        val czk = report.currencies.single { it.currency == "CZK" }
        assertTrue(czk.withinTolerance)
    }

    @Test
    fun `a currency present on only one side reconciles against an implicit zero`() {
        val report = reconcile(
            ledger = mapOf("CZK" to BigDecimal("100.00")),
            booked = mapOf("USD" to BigDecimal("30.00")),
        )

        assertTrue(report.hasDrift)
        assertEquals(listOf("CZK", "USD"), report.driftedCurrencies)
        val czk = report.currencies.single { it.currency == "CZK" }
        assertEquals(0, czk.difference.compareTo(BigDecimal("-100.00"))) // 0 booked − 100 ledger
        val usd = report.currencies.single { it.currency == "USD" }
        assertEquals(0, usd.difference.compareTo(BigDecimal("30.00"))) // 30 booked − 0 ledger
    }

    @Test
    fun `a difference within tolerance is not flagged`() {
        val report = reconcile(
            ledger = mapOf("CZK" to BigDecimal("1000.00")),
            booked = mapOf("CZK" to BigDecimal("1000.01")),
            tolerance = BigDecimal("0.05"),
        )

        assertFalse(report.hasDrift)
        assertTrue(report.currencies.single().withinTolerance)
    }

    @Test
    fun `currencies are reported in sorted order`() {
        val report = reconcile(
            ledger = mapOf("USD" to BigDecimal("1"), "CZK" to BigDecimal("1"), "EUR" to BigDecimal("1")),
            booked = mapOf("USD" to BigDecimal("1"), "CZK" to BigDecimal("1"), "EUR" to BigDecimal("1")),
        )

        assertEquals(listOf("CZK", "EUR", "USD"), report.currencies.map { it.currency })
    }

    @Test
    fun `an empty universe yields an empty, drift-free report`() {
        val report = reconcile(ledger = emptyMap(), booked = emptyMap())

        assertFalse(report.hasDrift)
        assertTrue(report.currencies.isEmpty())
    }
}
