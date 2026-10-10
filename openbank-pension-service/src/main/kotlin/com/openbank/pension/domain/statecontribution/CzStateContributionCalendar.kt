// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.statecontribution

import java.time.LocalDate
import java.time.YearMonth

/**
 * The CZ state-contribution calendar (ADR-0334, #12382). Every date here comes from Act 427/2011
 * Coll. (ZDPS), retrieved on 2026-10-09. The fact ids (C2, P2, R1–R5) refer to
 * `docs/research/cz-state-pension-contribution.md`. The calendar is pure, with no clock.
 * REQUIRES_LEGAL_REVIEW.
 */
// One function per statutory date (ZDPS §§16, 18): splitting them would scatter one calendar.
@Suppress("TooManyFunctions")
object CzStateContributionCalendar {

    private const val QUARTER_MONTHS = 3
    private const val PAYMENT_MONTHS_AFTER_QUARTER = 2L
    private const val TERMINATION_RETURN_MONTHS = 6L
    private const val RETURN_REPORT_DAY = 10
    private const val RETURN_RESULT_DAY = 20

    /** First month of the calendar quarter [month] falls in. A claim month is filed with its quarter (C2). */
    fun quarterStart(month: YearMonth): YearMonth =
        month.withMonth(((month.monthValue - 1) / QUARTER_MONTHS) * QUARTER_MONTHS + 1)

    fun quarterEnd(month: YearMonth): YearMonth = quarterStart(month).plusMonths(QUARTER_MONTHS - 1L)

    /** 1–4. */
    fun quarterOf(month: YearMonth): Int = (month.monthValue - 1) / QUARTER_MONTHS + 1

    /** The application for a quarter is filed in the calendar month after it (§16(2), C2). */
    fun filingMonth(claimMonth: YearMonth): YearMonth = quarterEnd(claimMonth).plusMonths(1)

    /** Last day the quarter's application may be filed (§16(2)). */
    fun filingDeadline(claimMonth: YearMonth): LocalDate = filingMonth(claimMonth).atEndOfMonth()

    /** Whether the quarter of [claimMonth] has closed on [today], so its claims may be filed. */
    fun fileable(claimMonth: YearMonth, today: LocalDate): Boolean = !today.isBefore(filingMonth(claimMonth).atDay(1))

    /** MF pays a quarter by the end of the second month after it (§18(1), P2). */
    fun expectedPaymentBy(claimMonth: YearMonth): LocalDate =
        quarterEnd(claimMonth).plusMonths(PAYMENT_MONTHS_AFTER_QUARTER).atEndOfMonth()

    /**
     * Unlawfully received money: by the end of the month in which one month has passed since
     * discovery (§18(2), R1).
     */
    fun unlawfulReturnDue(discoveredOn: LocalDate): LocalDate =
        YearMonth.from(discoveredOn.plusMonths(1)).atEndOfMonth()

    /** Unused money when the contract ends: by the end of the month in which six months have passed (§18(3), R2). */
    fun terminationReturnDue(terminatedOn: LocalDate): LocalDate =
        YearMonth.from(terminatedOn.plusMonths(TERMINATION_RETURN_MONTHS)).atEndOfMonth()

    /** The monthly return report is filed by the 10th (§18(4), R3). */
    fun returnReportDeadline(month: YearMonth): LocalDate = month.atDay(RETURN_REPORT_DAY)

    /** MF answers a return report by the 20th of the following month (§18(6), R4). */
    fun returnResultExpectedBy(reportMonth: YearMonth): LocalDate = reportMonth.plusMonths(1).atDay(RETURN_RESULT_DAY)

    /** After MF's result, the money goes back by the end of the month in which the result arrived (§18(7), R5). */
    fun returnSettlementDue(resultReceivedOn: LocalDate): LocalDate = YearMonth.from(resultReceivedOn).atEndOfMonth()
}
