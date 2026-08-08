// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.settlement

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.domain.calendar.BusinessCalendar
import com.openbank.libs.domain.calendar.BusinessDayConvention
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The booking (entry) date and the value date a payment settles on. */
data class SettlementDates(val bookingDate: LocalDate, val valueDate: LocalDate)

/**
 * Derives booking and value dates for a payment from the bank business-day calendars, the
 * submission cut-off, and the FX spot lag — replacing the previous `LocalDate.now()` shortcut.
 *
 * Rules:
 * - **Booking date**: today if it is a business day in the settlement-currency calendar and the
 *   request arrived before the daily cut-off; otherwise the next business day (weekends/holidays
 *   and post-cut-off submissions roll forward).
 * - **Earliest value date**: same-currency payments settle same day (T+0); cross-currency (FX)
 *   payments settle spot **T+2**, counted in days that are business days in *both* the payment and
 *   settlement calendars (a correct FX value date must be good in both currencies).
 * - **Requested value date**: honoured when it is on/after the earliest value date (rolled to a
 *   business day, FOLLOWING); a requested date earlier than the earliest is bumped up to it.
 */
object SettlementDateResolver {

    /**
     * The accounting zone the booking date is read in — owned by [AccountingClock.BANK_ZONE]
     * (ADR-0207 D1), not restated here. The booking date IS an accounting date: it is the day
     * a payment lands in the books, so it must be the same day the ledger and the year-close
     * think it is. Restating the zone locally is what let two components inside one service
     * disagree about the date for two hours a day, half the year.
     *
     * This is a same-value substitution: the constant is `Europe/Prague` on both sides, so no
     * booking or value date moves. What changes is that there is now one owner of the answer.
     */
    val BANK_ZONE: ZoneId = AccountingClock.BANK_ZONE

    /**
     * Daily submission cut-off, in [BANK_ZONE]. Deliberately NOT [AccountingClock.cutoff]: that
     * is the instant the *accounting day* rolls over (midnight), whereas this is the operational
     * deadline after which a payment received on a business day books to the next one. The two
     * are different business rules that happen to be expressed in the same units.
     */
    val DEFAULT_CUTOFF: LocalTime = LocalTime.of(16, 0)
    const val FX_SPOT_LAG_DAYS: Int = 2

    fun resolve(
        now: Instant,
        paymentCurrency: String,
        settlementCurrency: String,
        requestedValueDate: LocalDate? = null,
        zone: ZoneId = BANK_ZONE,
        cutoff: LocalTime = DEFAULT_CUTOFF,
    ): SettlementDates {
        val settlementCalendar = BusinessCalendar.forCurrency(settlementCurrency)
        val paymentCalendar = BusinessCalendar.forCurrency(paymentCurrency)

        val zoned = now.atZone(zone)
        val today = zoned.toLocalDate()
        val afterCutoff = !zoned.toLocalTime().isBefore(cutoff)

        var bookingDate = settlementCalendar.roll(today, BusinessDayConvention.FOLLOWING)
        if (afterCutoff && bookingDate == today) {
            bookingDate = settlementCalendar.nextBusinessDay(bookingDate)
        }

        val crossCurrency = !paymentCurrency.equals(settlementCurrency, ignoreCase = true)
        val earliestValueDate = if (crossCurrency) {
            addJointBusinessDays(bookingDate, FX_SPOT_LAG_DAYS, settlementCalendar, paymentCalendar)
        } else {
            bookingDate
        }

        val requested = requestedValueDate?.let { settlementCalendar.roll(it, BusinessDayConvention.FOLLOWING) }
        val valueDate = if (requested != null && requested.isAfter(earliestValueDate)) requested else earliestValueDate

        return SettlementDates(bookingDate, valueDate)
    }

    /** Add [n] days that are business days in BOTH calendars (FX spot value-date rule). */
    private fun addJointBusinessDays(start: LocalDate, n: Int, a: BusinessCalendar, b: BusinessCalendar): LocalDate {
        var date = start
        var remaining = n
        while (remaining > 0) {
            date = date.plusDays(1)
            while (!(a.isBusinessDay(date) && b.isBusinessDay(date))) {
                date = date.plusDays(1)
            }
            remaining--
        }
        return date
    }
}
