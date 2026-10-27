// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import com.openbank.libs.finance.calendar.Calendars
import com.openbank.libs.finance.calendar.FinanceCalendar
import java.time.LocalDate

/**
 * The deal's business days (ADR-0344 D1, closing #11670 for treasury): the kernel calendar sets
 * through `openbank-libs-finance` — CERTIS for CZK, TARGET2 for EUR, joined where a deal needs both
 * settlement systems open. This replaces the service's private weekend-only calendar; holidays now
 * roll an overnight maturity and an FX spot date (e.g. CZK overnight from 23 December 2026 matures
 * on 28 December, not 24).
 */
object DealCalendars {
    private const val SPOT_LAG_BUSINESS_DAYS = 2

    fun forCurrency(currency: String): FinanceCalendar = Calendars.forCurrencies(currency)

    /** An overnight deal matures on the next business day of its currency's calendar. */
    fun overnight(valueDate: LocalDate, currency: String): LocalDate = forCurrency(currency).nextBusinessDay(valueDate)

    /** FX spot: T+2 business days on the joint calendar of CZK and the foreign currency. */
    fun spotDate(tradeDate: LocalDate, foreign: String): LocalDate =
        Calendars.forCurrencies(Deal.CZK, foreign).addBusinessDays(tradeDate, SPOT_LAG_BUSINESS_DAYS)

    fun isSpotBusinessDay(date: LocalDate, foreign: String): Boolean =
        Calendars.forCurrencies(Deal.CZK, foreign).isBusinessDay(date)
}

/**
 * A reference `calendar` record applied on top of a base calendar: extra closures, and holidays the
 * bank declares open. `openbank-libs-finance` supports extra closures only; removals are the one
 * thing this adapter adds (reported upstream), and it overrides nothing but [isBusinessDay].
 */
class OverlayCalendar(
    private val base: FinanceCalendar,
    private val extraHolidays: Set<LocalDate>,
    private val removedHolidays: Set<LocalDate>,
) : FinanceCalendar {
    override val name: String =
        base.name + (if (extraHolidays.isEmpty()) "" else "+X${extraHolidays.sorted()}") +
            (if (removedHolidays.isEmpty()) "" else "-R${removedHolidays.sorted()}")

    override fun isBusinessDay(date: LocalDate): Boolean = when {
        date in extraHolidays -> false
        date in removedHolidays -> date.dayOfWeek.value <= WEEKDAYS
        else -> base.isBusinessDay(date)
    }

    private companion object {
        const val WEEKDAYS = 5
    }
}

/** Open only when every member is open (several payment calendars on one leg or deal). */
class JointCalendar(private val members: List<FinanceCalendar>) : FinanceCalendar {
    init {
        require(members.isNotEmpty()) { "a joint calendar needs at least one member" }
    }

    override val name: String = members.joinToString("+") { it.name }

    override fun isBusinessDay(date: LocalDate): Boolean = members.all { it.isBusinessDay(date) }
}
