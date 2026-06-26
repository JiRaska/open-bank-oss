// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.lending

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Days-past-due (DPD) computation and arrears bucketing for the lending bounded context (ADR-0028).
 *
 * DPD drives both IFRS 9 staging ([Ifrs9]) and the CRR/EBA default definition (90 DPD), and feeds
 * AnaCredit arrears reporting. Pure date math — no persistence.
 */
enum class DelinquencyBucket(val label: String) {
    CURRENT("Current"),
    DPD_1_30("1–30 DPD"),
    DPD_31_60("31–60 DPD"),
    DPD_61_90("61–90 DPD"),
    DPD_90_PLUS("90+ DPD"),
}

object Delinquency {

    /**
     * Days past due as of [asOf], measured from the oldest still-unpaid installment due date.
     * Returns 0 when nothing is overdue (no unpaid installment, or its due date is today/in the future).
     */
    fun daysPastDue(oldestUnpaidDueDate: LocalDate?, asOf: LocalDate): Int {
        if (oldestUnpaidDueDate == null || !oldestUnpaidDueDate.isBefore(asOf)) return 0
        return ChronoUnit.DAYS.between(oldestUnpaidDueDate, asOf).toInt()
    }

    /** Classify a DPD count into the standard arrears bucket. */
    fun bucket(daysPastDue: Int): DelinquencyBucket {
        require(daysPastDue >= 0) { "DPD cannot be negative: $daysPastDue" }
        return when {
            daysPastDue == 0 -> DelinquencyBucket.CURRENT
            daysPastDue <= 30 -> DelinquencyBucket.DPD_1_30
            daysPastDue <= 60 -> DelinquencyBucket.DPD_31_60
            daysPastDue <= 90 -> DelinquencyBucket.DPD_61_90
            else -> DelinquencyBucket.DPD_90_PLUS
        }
    }

    /**
     * The CRR Art. 178 / EBA default trigger: an exposure is in default once it is more than
     * [defaultThresholdDpd] days past due (default 90). Materiality thresholds are applied upstream.
     */
    fun isDefaulted(daysPastDue: Int, defaultThresholdDpd: Int = 90): Boolean = daysPastDue > defaultThresholdDpd
}
