// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.domain.report

import com.openbank.anacredit.domain.model.InstrumentType
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One row of the AnaCredit credit + financial dataset, as of a monthly reference date.
 *
 * Attribute meanings (ECB AnaCredit Reporting Manual, Part II):
 * - [outstandingNominalAmount] — drawn principal owed at the reference date.
 * - [offBalanceSheetAmount]    — undrawn committed amount (the off-balance-sheet exposure).
 * - [arrearsAmount]            — amount past due at the reference date.
 * - [defaultStatus]            — "DEFAULT" / "NOT_IN_DEFAULT" of the instrument.
 */
data class AnaCreditCreditRecord(
    val instrumentId: String,
    val debtorId: String,
    val instrumentType: InstrumentType,
    val currency: String,
    val outstandingNominalAmount: BigDecimal,
    val offBalanceSheetAmount: BigDecimal,
    val arrearsAmount: BigDecimal,
    val defaultStatus: String,
    val referenceDate: LocalDate,
)

/** Audit trail of an instrument that was *not* reported, with the eligibility reason code. */
data class ExclusionNote(
    val instrumentId: String,
    val debtorId: String,
    val reason: String,
)

/**
 * The materialised AnaCredit credit-dataset return for one reference date: the reportable [records]
 * plus the [exclusions] that explain every instrument that was dropped.
 */
data class AnaCreditReturn(
    val referenceDate: LocalDate,
    val records: List<AnaCreditCreditRecord>,
    val exclusions: List<ExclusionNote>,
) {
    val reportableCount: Int get() = records.size
    val excludedCount: Int get() = exclusions.size
}
