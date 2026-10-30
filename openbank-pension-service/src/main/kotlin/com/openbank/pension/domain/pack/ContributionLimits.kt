// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.pack

import com.openbank.pension.domain.model.ContributionFrequency
import java.math.BigDecimal

/**
 * What a participant may set as their own contribution schedule under a pack (ADR-0334, #12376).
 * Amounts are MONTHLY EQUIVALENTS, so a quarterly or annual payment is judged by the same bound.
 * A pack without this section refuses every schedule change (fails closed): a missing bound is
 * not "unbounded".
 */
data class ContributionLimits(
    val minMonthly: BigDecimal,
    /** Absent = only the service-wide input bound applies. */
    val maxMonthly: BigDecimal? = null,
    val allowedFrequencies: Set<ContributionFrequency>,
    val minDayOfMonth: Int = 1,
    /** 28 at most, so the collection day exists in every month. */
    val maxDayOfMonth: Int = LAST_SAFE_DAY,
    /** A change takes effect at the first collection day at least this many days ahead. */
    val changeNoticeDays: Int = DEFAULT_NOTICE_DAYS,
    /** How far ahead a requested start date may lie. */
    val maxStartAheadDays: Int = DEFAULT_MAX_AHEAD_DAYS,
) {
    init {
        require(minMonthly.signum() >= 0) { "contributionLimits.minMonthly must not be negative" }
        require(maxMonthly == null || maxMonthly >= minMonthly) {
            "contributionLimits.maxMonthly must be >= minMonthly"
        }
        require(allowedFrequencies.isNotEmpty()) { "contributionLimits.allowedFrequencies must not be empty" }
        require(minDayOfMonth in 1..maxDayOfMonth && maxDayOfMonth <= LAST_SAFE_DAY) {
            "contributionLimits days must satisfy 1 <= minDayOfMonth <= maxDayOfMonth <= $LAST_SAFE_DAY"
        }
        require(changeNoticeDays >= 0) { "contributionLimits.changeNoticeDays must be >= 0" }
        require(maxStartAheadDays > 0) { "contributionLimits.maxStartAheadDays must be > 0" }
    }

    companion object {
        const val LAST_SAFE_DAY = 28
        const val DEFAULT_NOTICE_DAYS = 3
        const val DEFAULT_MAX_AHEAD_DAYS = 366
    }
}
