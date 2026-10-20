// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance

import com.openbank.libs.domain.calendar.BusinessCalendar
import com.openbank.libs.domain.calendar.BusinessDayConvention
import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.finance.calendar.Calendars
import com.openbank.libs.finance.calendar.FinanceCalendar
import com.openbank.libs.finance.leg.DateRules
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.NotionalSpec
import com.openbank.libs.finance.leg.PayReceive
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.leg.ScheduleSpec
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.libs.finance.schedule.StubType
import java.math.BigDecimal
import java.time.LocalDate

val CZK: CurrencyCode = CurrencyCode.of("CZK")
val EUR: CurrencyCode = CurrencyCode.of("EUR")
val TARGET2: FinanceCalendar = Calendars.of(BusinessCalendar.target2())
val CERTIS: FinanceCalendar = Calendars.of(BusinessCalendar.certis())
val WEEKENDS: FinanceCalendar = Calendars.of(BusinessCalendar.weekendOnly())

fun d(iso: String): LocalDate = LocalDate.parse(iso)

fun bd(v: String): BigDecimal = BigDecimal(v)

/** A fixed-rate EUR leg on TARGET2 with every field defaulted — override what a test is about. */
fun fixedLeg(
    start: String,
    end: String,
    frequency: Frequency = Frequency.QUARTERLY,
    rate: RateSpec = RateSpec.Fixed(bd("0.036")),
    notional: NotionalSpec = NotionalSpec(bd("1000000"), finalExchange = false),
    dates: DateRules = DateRules(TARGET2, BusinessDayConvention.MODIFIED_FOLLOWING),
    stub: StubType = StubType.SHORT_INITIAL,
    dayCount: DayCount = DayCount.ACT_360,
): LegSpec = LegSpec(
    payReceive = PayReceive.RECEIVE,
    currency = EUR,
    notional = notional,
    schedule = ScheduleSpec(d(start), d(end), frequency, stub = stub),
    dates = dates,
    rate = rate,
    dayCount = dayCount,
)
