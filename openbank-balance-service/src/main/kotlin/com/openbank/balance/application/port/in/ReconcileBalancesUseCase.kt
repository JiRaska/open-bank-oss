// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.application.port.`in`

import com.openbank.balance.domain.reconciliation.ReconciliationReport
import java.time.LocalDate

/**
 * Inbound port for the ADR-0039 Phase A control-account ⇄ sub-ledger reconciliation. Read-only: it
 * compares the ledger deposit-control balances against the balance-service booked sums, records the
 * run, and surfaces drift. It mutates no balance.
 */
interface ReconcileBalancesUseCase {

    /** Run the tie-out as of [asOf] (default: today), persist the result, and return the report. */
    suspend fun reconcile(asOf: LocalDate): ReconciliationReport

    /** The most recent persisted reconciliation run, if any. */
    suspend fun latest(): ReconciliationReport?
}
