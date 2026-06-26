// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.domain.reconcile

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ReconciliationPolicyTest {

    @Test
    fun `opening plus movement equal to reported closing reconciles`() {
        val r = ReconciliationPolicy.reconcile(
            openingBalance = BigDecimal("100.00"),
            netMovement = BigDecimal("25.00"),
            reportedClosing = BigDecimal("125.00"),
        )
        assertThat(r).isInstanceOf(ReconciliationPolicy.Result.Reconciled::class.java)
        assertThat((r as ReconciliationPolicy.Result.Reconciled).closingBalance).isEqualByComparingTo("125.00")
    }

    @Test
    fun `scale differences do not spuriously fail the reconciliation`() {
        // 100 + 0 vs reported 100.0000 — same value, different scale.
        val r = ReconciliationPolicy.reconcile(BigDecimal("100"), BigDecimal.ZERO, BigDecimal("100.0000"))
        assertThat(r).isInstanceOf(ReconciliationPolicy.Result.Reconciled::class.java)
    }

    @Test
    fun `a disagreement with balance-service is a fail-closed mismatch with the delta`() {
        val r = ReconciliationPolicy.reconcile(
            openingBalance = BigDecimal("100.00"),
            netMovement = BigDecimal("25.00"),
            reportedClosing = BigDecimal("130.00"),
        )
        assertThat(r).isInstanceOf(ReconciliationPolicy.Result.Mismatch::class.java)
        val m = r as ReconciliationPolicy.Result.Mismatch
        assertThat(m.computed).isEqualByComparingTo("125.00")
        assertThat(m.reported).isEqualByComparingTo("130.00")
        assertThat(m.delta).isEqualByComparingTo("-5.00")
    }

    @Test
    fun `negative movement reconciles when reported closing matches`() {
        val r = ReconciliationPolicy.reconcile(BigDecimal("100"), BigDecimal("-40"), BigDecimal("60"))
        assertThat(r).isInstanceOf(ReconciliationPolicy.Result.Reconciled::class.java)
    }
}
