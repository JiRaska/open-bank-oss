// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.calendar

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The single supported answer to "what accounting day is it" (ADR-0207 D1).
 *
 * Pure domain primitive — no framework surface, so it lives in `openbank-libs-domain` per the
 * ADR-0122 domain/runtime split.
 *
 * ## Why this type exists
 *
 * The accounting date was previously derived from a wall clock by each caller independently, and
 * the callers did not agree. Measured on `origin/main` when ADR-0207 was written: 45 services
 * produce a CDI `Clock` bean and every one is `Clock.systemUTC()`, while five services
 * (`ledger`, `transaction`, `fx`, `dispute`, `simulation`) additionally construct
 * `Clock.system(ZoneId.of("Europe/Prague"))` in application code. `openbank-ledger-service` ran
 * both regimes inside one service: `ClockProducer` produced UTC while `LedgerService` and
 * `YearCloseService` built a Prague clock in their constructors and never consulted the injected
 * bean. Between 22:00 and 00:00 UTC in summer the two disagree about what day it is, so every
 * "today", every close cutoff and every default `entryDate` was two hours out of step with every
 * other for two hours a day, half the year. Nothing detected it, because both answers are
 * individually plausible and no third party knew which was right.
 *
 * The fix is not "pick a timezone". It is that an **accounting date is a domain value with its
 * own calendar and cutoff**, not a projection of wall-clock time — so it needs a type that says
 * so, and one owner. Wall-clock UTC stays correct for *timestamps*; this type is only about the
 * accounting day. The 45 `ClockProducer` beans are deliberately untouched.
 *
 * ## Cutoff
 *
 * [cutoff] is the wall-clock time in [zone] at which the accounting day rolls over. With the
 * default [DAY_START] (midnight) the accounting day equals the calendar date in [zone], which is
 * the behaviour every existing caller already assumes — adopting this type changes no dates. A
 * later cutoff (say 01:00) makes a post-midnight batch book to the previous accounting day, which
 * is what an operational cutoff means; it is configuration, not a code change.
 *
 * ## Determinism
 *
 * Every method derives from the injected [Clock], so a test supplies `Clock.fixed(...)` and gets
 * a deterministic accounting day (ADR-0100 Layer 1). Nothing here calls `Instant.now()` or
 * `LocalDate.now()` without a clock — that is the defect this type replaces, and
 * `.github/scripts/check-accounting-clock.py` enforces it for money-path services.
 */
class AccountingClock(
    private val clock: Clock,
    val zone: ZoneId = BANK_ZONE,
    val cutoff: LocalTime = DAY_START,
) {

    /** The current accounting day. */
    fun today(): LocalDate = accountingDayOf(clock.instant())

    /** The accounting day [instant] falls in, honouring [zone] and [cutoff]. */
    fun accountingDayOf(instant: Instant): LocalDate {
        val local = instant.atZone(zone)
        // Before the cutoff we are still transacting the previous accounting day. With the
        // default midnight cutoff this branch is unreachable (nothing is < 00:00), so the
        // accounting day is simply the calendar date in the bank zone.
        return if (local.toLocalTime() < cutoff) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    /** Wall-clock instant from the same source, for timestamps (not accounting dates). */
    fun instant(): Instant = clock.instant()

    /** True if [date] is in the future relative to [today] — never a legal accounting date. */
    fun isFuture(date: LocalDate): Boolean = date.isAfter(today())

    companion object {
        /**
         * The bank's accounting time zone. Czech entity, CNB-supervised: the books are kept in
         * Prague local time, which is what `LedgerService` and `YearCloseService` already assumed
         * — this constant makes that assumption owned rather than restated per class.
         */
        val BANK_ZONE: ZoneId = ZoneId.of("Europe/Prague")

        /** Default cutoff: the accounting day is the calendar day in [BANK_ZONE]. */
        val DAY_START: LocalTime = LocalTime.MIDNIGHT

        /** The production accounting clock: bank zone, midnight cutoff. */
        fun bank(clock: Clock): AccountingClock = AccountingClock(clock, BANK_ZONE, DAY_START)
    }
}
