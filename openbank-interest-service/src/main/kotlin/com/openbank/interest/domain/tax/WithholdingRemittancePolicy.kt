// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.tax

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

/**
 * Pure assembly of a monthly withholding-tax remittance (ADR-0038, §38d ZDP). Framework-free:
 * carries **zero** infrastructure imports (ADR-0002 domain-layer rule).
 *
 * Selects the **remittable** withholding records for a tax month and folds them into a single
 * [WithholdingRemittance] aggregate — the basis for the *Vyúčtování daně vybírané srážkou*.
 */
object WithholdingRemittancePolicy {

    /** The single Czech tax authority in v1 (finanční úřad / finanční správa). */
    const val CZ_TAX_AUTHORITY = "CZ-FU"

    /** Remittance is settled in CZK; non-CZK withholding is [WithholdingTreatment.DEFERRED_FX] upstream. */
    const val REMITTANCE_CURRENCY = "CZK"

    /**
     * A record is remittable when tax was actually withheld and still owed: it is [WithholdingTaxStatus.RECORDED]
     * (not yet remitted, not reversed), [WithholdingTreatment.WITHHELD] (NOT_WITHHELD / EXEMPT / DEFERRED_FX
     * carry no liability), settled in CZK, and its withholding month — `periodTo`, the §38d credit date — falls
     * in the target `(year, month)`.
     */
    fun isRemittable(record: WithholdingTax, year: Int, month: Int): Boolean =
        record.status == WithholdingTaxStatus.RECORDED &&
            record.treatment == WithholdingTreatment.WITHHELD &&
            record.currency == REMITTANCE_CURRENCY &&
            record.periodTo.year == year &&
            record.periodTo.monthValue == month

    /**
     * §38d odst. 3: the plátce remits withheld tax **by the end of the calendar month following** the
     * month in which the withholding obligation arose.
     */
    fun dueDate(year: Int, month: Int): LocalDate = YearMonth.of(year, month).plusMonths(1).atEndOfMonth()

    /**
     * Fold the remittable subset of [records] for `(year, month)` into a remittance aggregate. The
     * [totalTaxAmount] is the sum of whole-CZK tax amounts; an empty period yields a zero-amount,
     * zero-item batch (a documented nil return is still a return).
     */
    fun assemble(records: List<WithholdingTax>, year: Int, month: Int, now: OffsetDateTime): WithholdingRemittance {
        val remittable = records.filter { isRemittable(it, year, month) }
        val total = remittable.fold(BigDecimal.ZERO) { acc, r -> acc + r.taxAmount }
        return WithholdingRemittance(
            periodYear = year,
            periodMonth = month,
            totalTaxAmount = total,
            itemCount = remittable.size,
            dueDate = dueDate(year, month),
            withholdingIds = remittable.map { it.id },
            createdAt = now,
        )
    }
}
