// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.anacredit.domain.model

import java.math.BigDecimal
import java.time.LocalDate

/**
 * The type of counterparty that owes a credit instrument.
 *
 * AnaCredit (Reg. (EU) 2016/867) currently covers credit granted to **legal entities**.
 * An instrument whose debtor is a natural person (household / consumer) is out of scope.
 */
enum class CounterpartyType { LEGAL_ENTITY, NATURAL_PERSON }

/**
 * AnaCredit instrument type. v1 of the feed reports overdrafts only; loans/credit-card credit
 * (ADR-0028) plug additional types into the same builder later.
 */
enum class InstrumentType { OVERDRAFT, CREDIT_CARD_CREDIT, REVOLVING_CREDIT, LOAN }

/**
 * One credit instrument as the AnaCredit feed sees it, at a point in time.
 *
 * [committedAmount] is the arranged limit / commitment in the instrument's native [currency];
 * [drawnAmount] is the outstanding nominal actually drawn. [committedAmountEur] is the
 * EUR-equivalent commitment used solely for the €25 000 reporting threshold — FX sourcing is the
 * caller's responsibility (via openbank-fx-service); the native amounts are what the dataset reports.
 */
data class CreditExposure(
    val instrumentId: String,
    val debtorId: String,
    val debtorType: CounterpartyType,
    val instrumentType: InstrumentType,
    val currency: String,
    val committedAmount: BigDecimal,
    val drawnAmount: BigDecimal,
    val committedAmountEur: BigDecimal,
    val arrearsAmount: BigDecimal = BigDecimal.ZERO,
    val defaulted: Boolean = false,
    val originationDate: LocalDate,
) {
    /** Undrawn commitment reported as the off-balance-sheet amount; never negative. */
    val offBalanceSheetAmount: BigDecimal
        get() = (committedAmount - drawnAmount).max(BigDecimal.ZERO)
}
