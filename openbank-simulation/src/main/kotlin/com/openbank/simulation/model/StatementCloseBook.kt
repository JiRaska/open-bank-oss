// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import java.math.BigDecimal
import java.util.UUID

/**
 * One statement period-close attempt for `(accountId, currency)` — the `attemptId` dimension
 * keeps every step's key space-disjoint, the same reasoning [BillingFeeKey] documents for
 * `FeeBillingScenario`'s per-step-fresh `cycleId`.
 */
data class StatementCloseKey(val accountId: UUID, val currency: String, val attemptId: String)

/**
 * Simulated statement-close state (ADR-0035/0078, issue #667): every close ATTEMPT records two
 * independent facts — what [com.openbank.statement.domain.reconcile.ReconciliationPolicy] decided
 * (`Reconciled` vs `Mismatch`) and whether a `StatementPeriod` was actually persisted — so
 * `MoneyPathInvariants.statementCloseIntegrity` can assert they never disagree: a period is
 * persisted *if and only if* reconciliation succeeded. This is the fail-closed guarantee ADR-0035
 * §E exists to provide ("no partial, self-inconsistent legal document is ever produced"), checked
 * as a cross-side reconciliation — the same both-sides-recorded-independently shape as
 * [BillingFeeLedger] — rather than trusted by construction.
 *
 * Separately tracks, per `(accountId, currency)` (no attempt dimension — this is running state,
 * not a per-attempt fact), the last successfully closed period's closing balance, the ledger net
 * movement at that close, and the next legal/electronic sequence number to assign — so a
 * subsequent close attempt computes its opening balance and net-movement-since-last-close exactly
 * as the real `StatementService.mintPeriod`/`openingBalance` would.
 */
class StatementCloseBook {
    private val reconciled = mutableMapOf<StatementCloseKey, Boolean>()
    private val persisted = mutableMapOf<StatementCloseKey, Boolean>()

    private val lastClosingBalance = mutableMapOf<AccountCurrency, BigDecimal>()
    private val netAtLastClose = mutableMapOf<AccountCurrency, BigDecimal>()
    private val nextSequence = mutableMapOf<AccountCurrency, Long>()

    fun recordDecision(key: StatementCloseKey, wasReconciled: Boolean) {
        reconciled[key] = wasReconciled
    }

    fun recordPersisted(key: StatementCloseKey, wasPersisted: Boolean) {
        persisted[key] = wasPersisted
    }

    /** Every close attempt seen — the invariant checks the full set, not just one side. */
    fun attempts(): Set<StatementCloseKey> = reconciled.keys + persisted.keys

    fun wasReconciled(key: StatementCloseKey): Boolean = reconciled.getOrDefault(key, false)

    fun wasPersisted(key: StatementCloseKey): Boolean = persisted.getOrDefault(key, false)

    /** Opening balance for the NEXT close: the prior close's closing, else `openingBooked`. */
    fun openingBalanceOf(key: AccountCurrency, fallback: BigDecimal): BigDecimal =
        lastClosingBalance.getOrDefault(key, fallback)

    /** The ledger's cumulative net movement as of the last successful close (0 if never closed). */
    fun netAtLastCloseOf(key: AccountCurrency): BigDecimal = netAtLastClose.getOrDefault(key, BigDecimal.ZERO)

    fun nextSequenceOf(key: AccountCurrency): Long = nextSequence.getOrDefault(key, 1L)

    /** Advance the running state after a successful close — never called on a Mismatch. */
    fun advance(key: AccountCurrency, closingBalance: BigDecimal, cumulativeNet: BigDecimal) {
        lastClosingBalance[key] = closingBalance
        netAtLastClose[key] = cumulativeNet
        nextSequence[key] = nextSequenceOf(key) + 1
    }
}
