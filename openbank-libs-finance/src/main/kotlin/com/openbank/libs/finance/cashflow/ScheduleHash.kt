// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.cashflow

import com.openbank.libs.finance.leg.AmortisationSpec
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.schedule.Schedule
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Deterministic version hash of a schedule (ADR-0344 D4): SHA-256, lower-case hex, over a
 * canonical string of the leg spec, the calendars' effective content over the schedule's date
 * range, and the fixings the projection used.
 *
 * Canonical means: fields in a fixed order, decimals in plain notation stripped of trailing zeros
 * (`0.0350` and `0.035` hash equally — they are the same rate), dates ISO-8601, and calendars by
 * [com.openbank.libs.finance.calendar.FinanceCalendar.fingerprint] rather than by name alone, so a
 * bank-maintained holiday added inside the range changes the hash while one outside it does not.
 */
object ScheduleHash {
    private const val HEX_MASK = 0xff
    private const val CALENDAR_MARGIN_DAYS = 31L

    fun of(schedule: Schedule, fixingsUsed: List<UsedFixing> = emptyList()): String {
        val digest = MessageDigest.getInstance(
            "SHA-256",
        ).digest(canonical(schedule, fixingsUsed).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and HEX_MASK) }
    }

    fun of(schedule: Schedule, projection: CashFlowProjection): String = of(schedule, projection.fixingsUsed)

    /** The exact string that is hashed; exposed so a mismatch can be diffed rather than guessed at. */
    fun canonical(schedule: Schedule, fixingsUsed: List<UsedFixing>): String {
        val leg = schedule.leg
        val from = leg.schedule.start.minusDays(CALENDAR_MARGIN_DAYS)
        val to = (schedule.periods.maxOfOrNull { it.paymentDate } ?: leg.schedule.end).plusDays(CALENDAR_MARGIN_DAYS)
        return buildString {
            append("v1|").append(legPart(leg))
            append("|cal=").append(leg.dates.calendar.fingerprint(from, to))
            (leg.rate as? RateSpec.Floating)?.let {
                append("|fixcal=").append(it.fixing.calendar.fingerprint(from, to))
            }
            append("|fixings=")
            fixingsUsed.sortedWith(compareBy({ it.index }, { it.fixingDate }))
                .joinToString(";") { "${it.index}@${it.fixingDate}=${dec(it.value)}" }
                .let { append(it) }
        }
    }

    private fun legPart(leg: LegSpec): String = listOf(
        leg.payReceive.name,
        leg.currency.code,
        notionalPart(leg),
        with(leg.schedule) {
            "${d(start)}/${d(end)}/${accrualFrequency.token}/${paymentFrequency.token}/$stub/eom=$endOfMonth"
        },
        with(leg.dates) { "$roll/adjAcc=$adjustAccrualPeriod/adjPay=$adjustPaymentDate/lag=$paymentLagBusinessDays" },
        ratePart(leg.rate),
        leg.dayCount.name,
        leg.rounding.name,
    ).joinToString("|")

    private fun notionalPart(leg: LegSpec): String = with(leg.notional) {
        val amort = when (val a = amortisation) {
            AmortisationSpec.Bullet -> "BULLET"
            AmortisationSpec.Linear -> "LINEAR"
            AmortisationSpec.Annuity -> "ANNUITY"
            is AmortisationSpec.Custom -> "CUSTOM(" + a.repayments.joinToString(",") { dec(it) } + ")"
        }
        "${dec(amount)}/$amort/ix=$initialExchange/fx=$finalExchange/fxa=${finalExchangeAmount?.let { dec(it) }}"
    }

    private fun ratePart(rate: RateSpec): String = when (rate) {
        RateSpec.None -> "NONE"
        is RateSpec.Fixed -> "FIXED(${dec(rate.rate)})"
        is RateSpec.Floating -> with(rate) {
            "FLOAT($index,${dec(spread)},lag=${fixing.lagBusinessDays},arrears=${fixing.inArrears}," +
                "${resetFrequency.token},cap=${bounds.cap?.let { dec(it) }},floor=${bounds.floor?.let { dec(it) }}," +
                "${bounds.appliesTo},ruff=${rateUntilFirstFixing?.let { dec(it) }})"
        }
    }

    private fun dec(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()

    private fun d(v: LocalDate): String = v.toString()
}
