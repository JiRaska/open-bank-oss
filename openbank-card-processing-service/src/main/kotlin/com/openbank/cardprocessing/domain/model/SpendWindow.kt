// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.domain.model

import com.openbank.libs.domain.calendar.AccountingClock
import java.time.Clock
import java.time.Instant

/**
 * The two cumulative windows a card limit is measured over, resolved to instants once per decision.
 *
 * Resolved **once** and passed down, rather than each counter deriving its own "now": a daily and a
 * monthly figure computed a few milliseconds apart can straddle a day boundary, and the pair then
 * describes two different worlds. Same reasoning as delegation's `SpendCeilings`.
 *
 * The boundaries are the **accounting** day and month (ADR-0207, [AccountingClock]), not UTC
 * midnight. A card limit is a promise to a customer in a country, and the customer's day ends when
 * the bank's does; deriving it from UTC puts two hours of every summer evening in the wrong day,
 * which is the exact defect ADR-0207 was written for.
 */
data class SpendWindow(val dayStart: Instant, val monthStart: Instant, val now: Instant) {
    companion object {
        fun resolve(clock: Clock, accountingClock: AccountingClock = AccountingClock(clock)): SpendWindow {
            val today = accountingClock.today()
            return SpendWindow(
                dayStart = today.atStartOfDay(accountingClock.zone).toInstant(),
                monthStart = today.withDayOfMonth(1).atStartOfDay(accountingClock.zone).toInstant(),
                now = clock.instant(),
            )
        }
    }
}

/**
 * What the card has already spent inside [SpendWindow], as this service measures it.
 *
 * These numbers are the reason card-processing exists as a service rather than an endpoint. Until
 * it did, `POST /cards/{id}/authorizations` took the spend figures **from its caller** — and had no
 * caller, so the figures were never anything. A limit checked against a number the requester
 * supplies is not a limit.
 */
data class CountedSpend(
    val todayMinorUnits: Long,
    val thisMonthMinorUnits: Long,
    val thisMonthInCategoryMinorUnits: Long,
)
