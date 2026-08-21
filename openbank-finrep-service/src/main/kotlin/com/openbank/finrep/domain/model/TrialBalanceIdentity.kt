// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.model

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import java.math.BigDecimal

/**
 * The double-entry identity of the trial balance a FINREP template was rendered from (issue #5987).
 *
 * ## Why this exists, and why the obvious check would have been worthless
 *
 * `FinrepTemplate.isBalanced` used to be the literal `true` in both producers. The tempting repair —
 * recompute `assets − liabilities − equity == 0` inside F01.01 — is **vacuous**, because F01.01
 * derived equity AS `assets − liabilities`. An identity checked against a quantity defined as its
 * own residual can only ever hold, so the "fix" would have reported health for the same structural
 * reason the hardcoded literal did, while looking like a control. That is the defect one level up.
 *
 * The independent quantity is the trial balance itself. openbank-ledger-service publishes every GL
 * line with `net = totalDebit − totalCredit` **uniformly across all five account types** (see
 * `TrialBalanceLine.net` there), so for a ledger whose journals all balance:
 *
 *     Σ net over ALL lines == Σ totalDebit − Σ totalCredit == 0
 *
 * per currency. Nothing any FINREP mapper computes implies that sum. It is falsifiable by data the
 * mappers never look at: F01.01 reads only ASSET/LIABILITY/EQUITY and F02.00 only INCOME/EXPENSE, so
 * a residual can be introduced from the half of the trial balance the template ignores and the
 * check still sees it. What it catches in practice is what a reporting-side control is FOR: a
 * truncated or paginated line list, a filtered or partially-deserialised response, an account type
 * outside the five, and any ledger-side posting defect that survived to the frozen evidence.
 *
 * ## Per currency, never summed across currencies
 *
 * Residuals are kept per currency and every currency must hold independently. Summing them would
 * add CZK to EUR — meaningless as accounting, and weaker as a check: a lost EUR line could be
 * cancelled by an unrelated CZK one. The grouping is what makes the check unable to be satisfied
 * by coincidence.
 */
object TrialBalanceIdentity {

    /**
     * Residual `Σ net` per currency. Every entry is zero for a trial balance that balances; any
     * non-zero entry is the amount by which that currency fails to tie out, signed as
     * debit-minus-credit. Returned rather than reduced to a boolean so a caller (a metric, an
     * operator-facing message, a future validation-rule report) can say *how far off* and *where*.
     */
    fun residualsByCurrency(lines: List<TrialBalanceLineDto>): Map<String, BigDecimal> = lines
        .groupBy { it.currency }
        .mapValues { (_, group) -> group.fold(BigDecimal.ZERO) { acc, line -> acc.add(line.net) } }

    /**
     * Whether the double-entry identity holds for every currency present.
     *
     * An EMPTY trial balance holds trivially, and that is deliberate rather than an oversight: an
     * absent trial balance is an under-reporting problem, not a balance problem, and it already has
     * its own detector in `openbank.finrep.trial_balance.lines` (a rendered template of honest zeros
     * is exactly what that summary exists to make visible). Reporting `isBalanced = false` for it
     * would collapse two different defects onto one flag — the failure mode `PushResult.skipped()`
     * shipped in openbank-notification-service.
     *
     * `compareTo`, never `equals`: `BigDecimal("0.00") != BigDecimal.ZERO` by `equals` because the
     * scales differ, so an `equals` check would report a perfectly balanced ledger as unbalanced the
     * moment any line carried decimal places — which every real money line does.
     */
    fun holds(lines: List<TrialBalanceLineDto>): Boolean =
        residualsByCurrency(lines).values.all { it.compareTo(BigDecimal.ZERO) == 0 }
}
