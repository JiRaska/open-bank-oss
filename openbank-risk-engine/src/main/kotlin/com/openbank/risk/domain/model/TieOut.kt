// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.model

import java.math.BigDecimal

enum class TieOutStatus { TIED_OUT, UNTIED }

/**
 * One (GL account, currency) where the snapshot's positions do not sum to the ledger.
 * [glAccountCode] is null for positions that could not be mapped to any GL account.
 */
data class TieOutMismatch(
    val glAccountCode: String?,
    val currency: String,
    val ledgerNet: BigDecimal,
    val positionsNet: BigDecimal,
) {
    val difference: BigDecimal get() = positionsNet.subtract(ledgerNet)
}

data class TieOutResult(val mismatches: List<TieOutMismatch>) {
    val status: TieOutStatus get() = if (mismatches.isEmpty()) TieOutStatus.TIED_OUT else TieOutStatus.UNTIED
}

/**
 * The reconciliation gate (ADR-0314 D3): per (GL account, currency) the sum of positions must
 * equal the trial balance EXACTLY. Zero tolerance, compared with [BigDecimal.compareTo] so that
 * `10.00` and `10` agree while `10.00` and `10.01` do not; `equals` would fail the first case on
 * scale alone.
 *
 * A key present on only one side counts as zero on the other, so a trial-balance account with no
 * positions and a position with no trial-balance account are both mismatches unless they net to
 * zero. An UNMAPPED position (no GL account) is always a mismatch, even at zero: a position the
 * snapshot cannot place is itself the defect.
 */
object TieOut {

    fun check(trialBalance: List<TrialBalanceLine>, positions: List<Position>): TieOutResult {
        val ledger = trialBalance
            .groupBy { it.glAccountCode to it.currency }
            .mapValues { (_, lines) -> lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.net) } }
        val mapped = positions.filter { it.glAccountCode != null }
            .groupBy { it.glAccountCode!! to it.currency }
            .mapValues { (_, ps) -> ps.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.amount) } }

        val keyed = (ledger.keys + mapped.keys)
            .sortedWith(compareBy({ it.first }, { it.second }))
            .mapNotNull { key ->
                val ledgerNet = ledger[key] ?: BigDecimal.ZERO
                val positionsNet = mapped[key] ?: BigDecimal.ZERO
                if (ledgerNet.compareTo(positionsNet) == 0) {
                    null
                } else {
                    TieOutMismatch(key.first, key.second, ledgerNet, positionsNet)
                }
            }
        val unmapped = positions.filter { it.glAccountCode == null }
            .groupBy { it.currency }
            .toSortedMap()
            .map { (currency, ps) ->
                TieOutMismatch(
                    glAccountCode = null,
                    currency = currency,
                    ledgerNet = BigDecimal.ZERO,
                    positionsNet = ps.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.amount) },
                )
            }
        return TieOutResult(keyed + unmapped)
    }
}
