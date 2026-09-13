// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.model

import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import java.math.BigDecimal

/**
 * The outcome of cross-checking finrep's own double-entry recomputation against the balance verdict
 * openbank-ledger-service published with the same frozen trial balance (issue #6011).
 *
 * Four values, never one boolean. A cross-check that answers only "balanced / not balanced" folds
 * three genuinely different defects onto one flag — the shape `PushResult.skipped()` shipped in
 * openbank-notification-service, where a disabled adapter reported `success = true` and every
 * push in an environment with no credentials was counted as delivered.
 */
enum class BalanceVerdict {
    /** Both sources agree the trial balance ties out. The only value that permits a submission. */
    AGREED_BALANCED,

    /**
     * Both sources agree it does NOT tie out. A ledger-side accounting defect: the evidence finrep
     * was given is internally consistent, and it is consistently wrong.
     */
    AGREED_IMBALANCED,

    /**
     * The two sources DISAGREE. Not an accounting defect — an evidence defect: the lines finrep
     * evaluated are not the lines ledger evaluated. See [TrialBalanceAssurance] for exactly how
     * that can happen.
     */
    SOURCES_DISAGREE,

    /**
     * Ledger published no verdict at all. Distinct from `false` on purpose: `false` is ledger
     * asserting an imbalance, absence is ledger asserting nothing, and treating the two alike would
     * report a shape change as an accounting failure (or, the other way round, let a response that
     * lost the field deserialize into a plausible `false`).
     */
    LEDGER_FLAG_ABSENT,
}

/**
 * Both sides of the check, kept separable so a consumer can tell which one objected.
 *
 * [ownIdentityHolds] is finrep's recomputation ([TrialBalanceIdentity]); [ledgerReportsBalanced] is
 * the producer's own assertion, `null` when the response carried none. [residualsByCurrency] says
 * by how much and in which currency finrep's side failed, so an operator gets a number rather than
 * a bit.
 */
data class BalanceAssessment(
    val ownIdentityHolds: Boolean,
    val ledgerReportsBalanced: Boolean?,
    val residualsByCurrency: Map<String, BigDecimal>,
    val verdict: BalanceVerdict,
)

/**
 * Cross-check of the two independent balance verdicts on one frozen trial balance (issue #6011).
 *
 * ## Why this is not the vacuous shape #6010 fixed
 *
 * The trap one level up is a check whose two sides move together, so it structurally cannot
 * disagree — `isBalanced = true` hardcoded (#5987), then the tempting repair of recomputing an
 * identity against a quantity defined as its own residual (#6010). This check has to survive the
 * same question: *what input makes the two sides differ?*
 *
 * They are computed from different columns, by different services, over different moments:
 *
 *  - ledger's flag is `Σ totalDebit == Σ totalCredit` over the whole line set **as the response
 *    object was constructed** (`PeriodTrialBalance.isBalanced`), a single scalar, with **no
 *    currency grouping**;
 *  - finrep's is `Σ net == 0` **per currency** over the lines that actually **arrived and
 *    deserialised**.
 *
 * Three concrete inputs make them differ, and all three are tested:
 *
 *  1. **A truncated response that is internally balanced.** Lines are lost after ledger computed
 *     its scalar — a paginated or capped `lines` array, a filtering proxy, a partial
 *     deserialisation — and the survivors happen to still sum to zero. finrep's recomputation is
 *     satisfied, ledger's flag (over the full set) is not. This is the failure mode a
 *     derive-only reporting service cannot otherwise see: it renders a well-formed 200 either way,
 *     of honest-looking but under-reported figures. `openbank.finrep.trial_balance.lines` only
 *     shows a collapse to zero, not a partial loss.
 *  2. **Offsetting residuals in different currencies.** A +100 CZK residual against a −100 EUR one
 *     sums to zero globally, so ledger's ungrouped scalar says balanced while finrep's per-currency
 *     identity does not. Journals balance per currency here (ADR-0025), so finrep is right and the
 *     disagreement is real information about the producer's weaker check.
 *  3. **A line whose `net` is not `totalDebit − totalCredit`** on the wire — a producer mapping
 *     defect, invisible to ledger's own flag, which never reads `net`.
 *
 * Case 1 is the one that motivated the issue, and note the direction: finrep's recomputation ALONE
 * passes it. Neither source can catch it alone; only the agreement can.
 *
 * ## Why absence fails closed
 *
 * [BalanceVerdict.LEDGER_FLAG_ABSENT] does not permit a submission. Ledger's frozen-trial-balance
 * response declares `balanced` unconditionally and the committed pact pins it, so an absent flag
 * means the response is not the response this service contracted for — which is precisely when a
 * regulatory return should not be produced as if nothing were wrong. It gets its own verdict rather
 * than being folded into `AGREED_IMBALANCED` so that "the contract changed" never reads as "the
 * bank's books do not balance".
 */
object TrialBalanceAssurance {

    fun assess(snapshot: TrialBalanceSnapshot): BalanceAssessment {
        val residuals = TrialBalanceIdentity.residualsByCurrency(snapshot.lines)
        val ownHolds = residuals.values.all { it.compareTo(BigDecimal.ZERO) == 0 }
        val ledgerSays = snapshot.ledgerReportsBalanced
        val verdict = when {
            ledgerSays == null -> BalanceVerdict.LEDGER_FLAG_ABSENT
            ledgerSays != ownHolds -> BalanceVerdict.SOURCES_DISAGREE
            ownHolds -> BalanceVerdict.AGREED_BALANCED
            else -> BalanceVerdict.AGREED_IMBALANCED
        }
        return BalanceAssessment(
            ownIdentityHolds = ownHolds,
            ledgerReportsBalanced = ledgerSays,
            residualsByCurrency = residuals,
            verdict = verdict,
        )
    }
}
