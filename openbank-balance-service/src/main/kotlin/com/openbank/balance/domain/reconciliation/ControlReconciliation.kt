// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.domain.reconciliation

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * ADR-0039 Phase A — read-only control-account ⇄ sub-ledger tie-out.
 *
 * The ledger's per-currency *deposit-control* account is a LIABILITY (the bank owes its customers);
 * its credit-normal balance (`credit − debit`) is what the bank owes in that currency. Balance-service
 * is, today, an independent writer of the per-customer booked balances that this control account is
 * supposed to summarise. This pure tie-out compares, per currency, the ledger control balance against
 * the sum of customer booked balances and reports any drift. It changes no state and asserts no
 * direction of truth — it only measures whether the two writers agree (the invariant Phase D will
 * eventually guarantee by construction).
 */

/** Per-currency comparison of the ledger control balance against the balance-service booked sum. */
data class CurrencyReconciliation(
    val currency: String,
    /** Ledger deposit-control credit-normal balance (credit − debit) for this currency. */
    val ledgerControlBalance: BigDecimal,
    /** Sum of balance-service booked amounts across all accounts in this currency. */
    val subLedgerBookedSum: BigDecimal,
    /** subLedgerBookedSum − ledgerControlBalance. Zero (within tolerance) means the two agree. */
    val difference: BigDecimal,
    val withinTolerance: Boolean,
)

/** A single reconciliation run across every currency seen on either side. */
data class ReconciliationReport(
    val asOf: LocalDate,
    val generatedAt: OffsetDateTime,
    val tolerance: BigDecimal,
    val currencies: List<CurrencyReconciliation>,
) {
    /** True when any currency drifted beyond tolerance — the alerting signal. */
    val hasDrift: Boolean get() = currencies.any { !it.withinTolerance }

    val driftedCurrencies: List<String> get() = currencies.filter { !it.withinTolerance }.map { it.currency }
}

/**
 * Pure assembly of a [ReconciliationReport]. No I/O, no framework — fully unit-testable.
 */
object ReconciliationPolicy {

    /** Default tolerance: exact tie-out. Rounding never applies (both sides are stored to scale). */
    val DEFAULT_TOLERANCE: BigDecimal = BigDecimal.ZERO

    /**
     * Reconcile the ledger control balances against the sub-ledger booked sums.
     *
     * Currencies are the union of both maps; a currency present on only one side is reconciled against
     * an implicit zero on the other (so a stray balance with no ledger backing, or vice versa, surfaces
     * as drift rather than silently dropping out).
     */
    fun reconcile(
        ledgerControlByCurrency: Map<String, BigDecimal>,
        subLedgerBookedByCurrency: Map<String, BigDecimal>,
        asOf: LocalDate,
        generatedAt: OffsetDateTime,
        tolerance: BigDecimal = DEFAULT_TOLERANCE,
    ): ReconciliationReport {
        val currencies = (ledgerControlByCurrency.keys + subLedgerBookedByCurrency.keys)
            .toSortedSet()
            .map { ccy ->
                val ledger = ledgerControlByCurrency[ccy] ?: BigDecimal.ZERO
                val subLedger = subLedgerBookedByCurrency[ccy] ?: BigDecimal.ZERO
                val difference = subLedger.subtract(ledger)
                CurrencyReconciliation(
                    currency = ccy,
                    ledgerControlBalance = ledger,
                    subLedgerBookedSum = subLedger,
                    difference = difference,
                    withinTolerance = difference.abs().compareTo(tolerance) <= 0,
                )
            }
        return ReconciliationReport(
            asOf = asOf,
            generatedAt = generatedAt,
            tolerance = tolerance,
            currencies = currencies,
        )
    }
}
