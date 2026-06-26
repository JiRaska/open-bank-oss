// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.domain.reconcile

import java.math.BigDecimal

/**
 * Fail-closed period-boundary reconciliation (ADR-0035 §E): a statement's closing balance is the
 * opening balance plus the period's booked net movement, and it **must equal** the closing balance
 * reported independently by balance-service for that pocket. A mismatch fails the period-close — no
 * partial, self-inconsistent legal document is ever produced — and raises an alert.
 *
 * Pure policy: no clock, no IO. Comparison uses [BigDecimal.compareTo] so scale differences
 * (`100` vs `100.00`) do not spuriously fail.
 */
object ReconciliationPolicy {

    sealed interface Result {
        /** Computed closing matches balance-service; the period may be closed at [closingBalance]. */
        data class Reconciled(val closingBalance: BigDecimal) : Result

        /** Computed and reported closing disagree by [delta]; the period MUST NOT be closed. */
        data class Mismatch(val computed: BigDecimal, val reported: BigDecimal, val delta: BigDecimal) : Result
    }

    fun reconcile(openingBalance: BigDecimal, netMovement: BigDecimal, reportedClosing: BigDecimal): Result {
        val computed = openingBalance.add(netMovement)
        return if (computed.compareTo(reportedClosing) == 0) {
            Result.Reconciled(computed)
        } else {
            Result.Mismatch(computed, reportedClosing, computed.subtract(reportedClosing))
        }
    }
}
