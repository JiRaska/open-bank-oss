// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.schedule

import java.time.LocalDate

/** A leg whose schedule would exceed [ScheduleLimits.MAX_PERIODS] periods; refused, never generated. */
class ScheduleTooLongException(val limit: Int, start: LocalDate, end: LocalDate, frequency: Frequency) :
    IllegalArgumentException(
        "schedule $start..$end at ${frequency.token} exceeds $limit periods per leg; " +
            "use a coarser frequency or a shorter leg",
    )

/**
 * Hard bounds on schedule generation, enforced INSIDE the grid loop so no caller — a pre-trade
 * pricing request, an event, a test — can make the engine allocate an unbounded schedule. 2,000
 * periods is 50 years of weekly or 166 years of monthly periods: far beyond any treasury product,
 * and a few milliseconds of work at the limit.
 */
object ScheduleLimits {
    const val MAX_PERIODS = 2_000

    internal fun check(boundariesSoFar: Int, start: LocalDate, end: LocalDate, frequency: Frequency) {
        if (boundariesSoFar >= MAX_PERIODS) throw ScheduleTooLongException(MAX_PERIODS, start, end, frequency)
    }
}
