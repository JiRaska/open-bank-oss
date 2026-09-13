// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.port.out

import java.math.BigDecimal
import java.time.LocalDate

/**
 * One GL account line of the ledger trial balance.
 *
 * [net] is `totalDebit − totalCredit`, ledger's own uniform convention for every account type — so
 * it is NEGATIVE for a credit-normal account (liabilities, equity, income) and positive for a
 * debit-normal one (assets, expenses). The mappers are responsible for presenting each FINREP row
 * in its reporting sign; [com.openbank.finrep.domain.model.TrialBalanceIdentity] relies on the raw
 * convention being uniform, which is what makes `Σ net == 0` the double-entry identity.
 *
 * [currency] is carried because the identity holds PER CURRENCY. Without it the check could be
 * satisfied by a CZK line cancelling a lost EUR one (issue #5987). It carries NO default on
 * purpose: a defaulted `"CZK"` would let a response that omits the field deserialize into a
 * plausible-looking line, silently merging every currency into one residual bucket — the check
 * would then still pass, for a reason nothing anywhere would report.
 */
data class TrialBalanceLineDto(val code: String, val accountType: String, val net: BigDecimal, val currency: String)

/**
 * One ledger trial-balance read: the lines, plus the balance verdict the PRODUCER published with
 * them (issue #6011).
 *
 * [ledgerReportsBalanced] used to be deserialised and dropped on the floor in `LedgerAdapter`. It is
 * carried now because agreement between it and finrep's own recomputation is a check neither side
 * can make alone — see [com.openbank.finrep.domain.model.TrialBalanceAssurance] for the three
 * inputs that make them disagree.
 *
 * NULLABLE, and no default: `null` means the response carried no verdict, which is a different fact
 * from a verdict of `false`. A non-null `Boolean` here would let jackson-module-kotlin coerce an
 * absent field to `false` and report a contract change as an accounting failure; a default of `true`
 * would do the opposite and re-publish the producer's assertion without the producer.
 */
data class TrialBalanceSnapshot(val lines: List<TrialBalanceLineDto>, val ledgerReportsBalanced: Boolean?)

/** Minimal closed-period metadata needed to decide whether a regulatory render is reproducible. */
data class ClosedPeriodDto(val periodType: String, val to: LocalDate, val status: String, val evidenceState: String)

interface LedgerPort {
    suspend fun getTrialBalance(asOf: LocalDate): TrialBalanceSnapshot

    /** Mutable period aggregate for an explicitly labelled internal working preview only. */
    suspend fun getLiveTrialBalance(asOf: LocalDate): TrialBalanceSnapshot

    suspend fun listClosedPeriods(): List<ClosedPeriodDto>
}
