// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * When a recurring campaign re-evaluates its segment and enrols whoever newly qualifies.
 *
 * A campaign was a one-shot until now: `POST /{id}/enrol` evaluated the segment once, and anyone who
 * qualified the next day was never contacted. A cadence makes the same call happen on a schedule, so
 * "welcome anyone who opened an account this week" is a campaign rather than a recurring human task.
 *
 * **Why a catalogue and not a cron string on the request.** The same reason [ConversionCatalog] and
 * [SegmentCatalog] are catalogues: a definition that can be typed into a text box cannot be reviewed,
 * versioned or diffed. Cron adds two failure modes of its own that a closed set removes outright — a
 * malformed expression is a schedule that silently never fires, and `* * * * *` is a self-inflicted
 * denial of service against the segment evaluator and notification-service. Adding a cadence is a
 * pull request against this file.
 *
 * **Why the time zone is on every entry rather than assumed.** `0 9 * * *` means nothing without
 * one. Temporal evaluates a cron in UTC unless told otherwise, so the "9am" campaign would reach
 * Czech customers at 11:00 in summer and 10:00 in winter — a bug that is invisible in every test,
 * because tests run in UTC and agree with the mistake. The zone is stated once here and travels with
 * the cadence into the Temporal schedule spec.
 */
object ScheduleCatalog {

    /**
     * The bank's operating zone. Marketing sends are timed against the customer's day, not the
     * cluster's — and every customer of this bank is in one zone, so a per-campaign override would
     * be configuration nobody would ever set differently.
     */
    const val ZONE = "Europe/Prague"

    /**
     * @param cron a five-field expression, evaluated in [ZONE].
     * @param humanForm what an operator sees in the console. Kept beside the expression rather than
     *   derived from it: a rendered cron is a different sentence in every library, and this string
     *   is what a marketer approves.
     */
    enum class Recurrence { DAILY, WEEKLY_MONDAY, MONTHLY_FIRST }

    data class Cadence(val cron: String, val humanForm: String, val recurrence: Recurrence)

    /**
     * The catalogue. Deliberately coarse — nothing finer than daily.
     *
     * A marketing campaign that re-enrols hourly is not a campaign, it is a loop, and every entry
     * here fans out to a segment evaluation plus one Temporal workflow start per newly-qualifying
     * party. The cheapest way to keep that honest is to not offer the frequency in the first place.
     */
    val ALL: Map<String, Cadence> = mapOf(
        "DAILY_MORNING" to Cadence("0 9 * * *", "every day at 09:00", Recurrence.DAILY),
        "WEEKLY_MONDAY_MORNING" to Cadence("0 9 * * MON", "every Monday at 09:00", Recurrence.WEEKLY_MONDAY),
        "MONTHLY_FIRST_MORNING" to Cadence("0 9 1 * *", "on the 1st of each month at 09:00", Recurrence.MONTHLY_FIRST),
    )

    fun exists(cadence: String): Boolean = cadence in ALL

    operator fun get(cadence: String): Cadence? = ALL[cadence]

    /**
     * The next declared marketing window, calculated from the same closed cadence the Temporal
     * scheduler receives. This is a planning projection, not a delivery promise: Temporal remains
     * the authority for whether a run starts and whether anybody is eligible when it does.
     */
    fun nextWindowAfter(cadence: String, after: Instant): Instant {
        val rule = requireNotNull(this[cadence]) { "unknown cadence '$cadence'" }
        val now = after.atZone(ZoneId.of(ZONE))
        val atNine = LocalTime.of(MORNING_HOUR, 0)
        fun candidate(day: ZonedDateTime): ZonedDateTime = day.with(atNine)
        val next = when (rule.recurrence) {
            Recurrence.DAILY -> candidate(now).takeIf { it.isAfter(now) } ?: candidate(now.plusDays(1))
            Recurrence.WEEKLY_MONDAY -> {
                val monday = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                candidate(monday).takeIf { it.isAfter(now) } ?: candidate(monday.plusWeeks(1))
            }
            Recurrence.MONTHLY_FIRST -> {
                val first = now.with(TemporalAdjusters.firstDayOfMonth())
                candidate(first).takeIf { it.isAfter(now) }
                    ?: candidate(now.plusMonths(1).with(TemporalAdjusters.firstDayOfMonth()))
            }
        }
        return next.toInstant()
    }

    private const val MORNING_HOUR = 9
}
