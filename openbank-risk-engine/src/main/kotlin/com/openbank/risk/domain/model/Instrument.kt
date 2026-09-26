// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.model

import com.openbank.libs.lending.AmortizationMethod
import com.openbank.risk.domain.curve.CurveIndex
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The closed set of instrument kinds (ADR-0314 D4). Every position the engine expands maps to one
 * of these; treasury instruments (ADR-0315) enter through the same set.
 *
 * Filled so far: [AMORTISING_LOAN] and [BULLET] (loans from lending) and [NON_MATURITY_DEPOSIT]
 * (customer balances on deposit control). The rest are DECLARED ONLY — no source fills them yet,
 * and a GL account nothing finer describes stays a GL-level position, never a guessed instrument.
 */
enum class InstrumentKind {
    AMORTISING_LOAN,
    BULLET,
    NON_MATURITY_DEPOSIT,
    TERM_DEPOSIT,
    CURRENT_ACCOUNT,
    FX_POSITION,
    CASH_NOSTRO,
    BOND,
    MONEY_MARKET_DEAL,
    DERIVATIVE_LEG,
    EQUITY_CAPITAL,
}

enum class RateType { FIXED, FLOATING }

/**
 * How an instrument's rate is set (ADR-0314 D5). [currentAnnualRate] is the rate applied now — for
 * FLOATING, index + spread at the last reset. [index], [spread], [resetFrequencyMonths] and
 * [nextResetDate] are FLOATING-only.
 */
data class RateTerms(
    val rateType: RateType,
    val currentAnnualRate: BigDecimal?,
    val index: CurveIndex? = null,
    val spread: BigDecimal? = null,
    val resetFrequencyMonths: Int? = null,
    val nextResetDate: LocalDate? = null,
)

/** One contractual installment still owed, as the owning service's schedule states it. */
data class ScheduledInstallment(
    val number: Int,
    val dueDate: LocalDate,
    val principal: BigDecimal,
    val interest: BigDecimal,
)

/** The kind-specific part of an instrument. */
sealed interface InstrumentExtension

/**
 * Loan extension: what the cash-flow engine needs beyond the common core. [remainingInstallments]
 * is lending's own schedule for the unpaid tail — the contract — so a FIXED loan's flows are those
 * installments, never a re-derivation that could differ by a rounding cent.
 */
data class LoanExtension(
    val method: AmortizationMethod,
    val periodsPerYear: Int,
    val remainingInstallments: List<ScheduledInstallment>,
) : InstrumentExtension {
    val remainingPeriods: Int get() = remainingInstallments.size
    val nextDueDate: LocalDate? get() = remainingInstallments.minOfOrNull { it.dueDate }
}

/**
 * One contract-level instrument of a snapshot (ADR-0314 D4): the common core plus an optional
 * kind-specific [extension].
 *
 * [outstanding] is in the TRIAL-BALANCE convention (debit − credit), the same as [Position.amount],
 * so a loan (an asset) is positive and a deposit (a liability) negative, and instruments sum
 * against the ledger directly. [counterpartyRef] is opaque: party identity stays in party-service.
 */
data class Instrument(
    val id: String,
    val kind: InstrumentKind,
    val glAccountCode: String?,
    val currency: String,
    val outstanding: BigDecimal,
    val valueDate: LocalDate?,
    val maturityDate: LocalDate?,
    val rateTerms: RateTerms?,
    val counterpartyRef: String?,
    val ifrs9Stage: String?,
    val extension: InstrumentExtension?,
)
