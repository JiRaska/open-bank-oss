// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.reserves

import com.openbank.risk.infrastructure.MinReservesConfig
import com.openbank.risk.infrastructure.toCalendar
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/** Maintenance-period averaging (ADR-0315 D8): average, coverage, proposal and every not-stated path. */
class ReserveAveragingTest {

    /** 10 calendar days, 2026-01-01..10: a requirement of 100 means 1000 held over the period. */
    private val period = MaintenancePeriod("P", d(1), d(10), LocalDate.parse("2025-12-31"))
    private val requirement = BigDecimal("100")

    private fun d(day: Int) = LocalDate.of(2026, 1, day)

    private fun held(vararg byDay: Pair<Int, String>) =
        byDay.map { (day, amount) -> DailyHolding(d(day), UUID.randomUUID(), BigDecimal(amount)) }

    private fun compute(
        evaluation: LocalDate,
        days: List<DailyHolding>,
        requirement: BigDecimal? = this.requirement,
        requirementNotStated: String? = null,
        holdingsNotStated: String? = null,
    ) = ReserveAveraging.compute(period, evaluation, requirement, requirementNotStated, holdingsNotStated, days)

    @Test
    fun `average is over the snapshot days and the proposal fills the rest of the period`() {
        val r = compute(d(4), held(1 to "50", 2 to "50", 3 to "50", 4 to "50"))

        assertThat(r.daysInPeriod).isEqualTo(10)
        assertThat(r.daysElapsed).isEqualTo(4)
        assertThat(r.daysRemaining).isEqualTo(6)
        assertThat(r.coverage).isEqualByComparingTo("1")
        assertThat(r.averageHoldings).isEqualByComparingTo("50")
        // (100 × 10 − 200) / 6 = 133.33…
        assertThat(r.remainingRequiredAverage!!.setScale(4, RoundingMode.HALF_EVEN)).isEqualByComparingTo("133.3333")
        assertThat(r.proposal!!.map { it.date }).containsExactly(d(5), d(6), d(7), d(8), d(9), d(10))
        // holding the proposal every remaining day meets the requirement on average exactly
        val total = BigDecimal("200") + r.proposal!!.sumOf { it.amount }
        assertThat(total.divide(BigDecimal(10), 10, RoundingMode.HALF_EVEN)).isEqualByComparingTo("100")
        assertThat(r.requirementMet).isNull()
    }

    @Test
    fun `a day without a snapshot lowers coverage and withholds the proposal, never a partial-sum proposal`() {
        val r = compute(d(4), held(1 to "80", 3 to "110", 4 to "120"))

        assertThat(r.daysWithData).isEqualTo(3)
        assertThat(r.coverage).isEqualByComparingTo("0.75")
        assertThat(r.missingDays).containsExactly(d(2))
        assertThat(r.averageHoldings!!.setScale(4, RoundingMode.HALF_EVEN)).isEqualByComparingTo("103.3333")
        assertThat(r.remainingRequiredAverage).isNull()
        assertThat(r.proposal).isNull()
        assertThat(r.proposalNotStated).isEqualTo(ReserveAveraging.GAPS_IN_COVERAGE)
    }

    @Test
    fun `holdings not stated - the average is null with the reason, never zero, while coverage is still stated`() {
        val reason = "no ČNB current-account GL"
        val days = listOf(DailyHolding(d(1), UUID.randomUUID(), null), DailyHolding(d(2), UUID.randomUUID(), null))
        val r = compute(d(2), days, holdingsNotStated = reason)

        assertThat(r.averageHoldings).isNull()
        assertThat(r.averageNotStated).isEqualTo(reason)
        assertThat(r.proposal).isNull()
        assertThat(r.proposalNotStated).isEqualTo(reason)
        assertThat(r.coverage).isEqualByComparingTo("1")
        assertThat(r.requirement).isEqualByComparingTo("100")
    }

    @Test
    fun `requirement not stated - no proposal, the reason is carried`() {
        val r = compute(d(2), held(1 to "100", 2 to "100"), requirement = null, requirementNotStated = "no base run")
        assertThat(r.averageHoldings).isEqualByComparingTo("100")
        assertThat(r.proposal).isNull()
        assertThat(r.proposalNotStated).isEqualTo("no base run")
    }

    @Test
    fun `already over-held - the remaining required average is negative and the proposal floors at zero`() {
        val r = compute(d(4), held(1 to "300", 2 to "300", 3 to "300", 4 to "300"))
        assertThat(r.remainingRequiredAverage!!.signum()).isNegative()
        assertThat(r.proposal!!.map { it.amount }.distinct()).containsExactly(BigDecimal.ZERO)
    }

    @Test
    fun `before the period starts nothing is elapsed and the whole requirement is proposed per day`() {
        val r = compute(LocalDate.parse("2025-12-20"), emptyList())
        assertThat(r.daysElapsed).isZero()
        assertThat(r.coverage).isNull()
        assertThat(r.averageNotStated).isEqualTo(ReserveAveraging.PERIOD_NOT_STARTED)
        assertThat(r.proposal).hasSize(10)
        assertThat(r.proposal!!.first().amount).isEqualByComparingTo("100")
    }

    @Test
    fun `a finished fully covered period gets a verdict, a finished gappy one does not`() {
        val all = (1..10).map { it to "100" }.toTypedArray()
        val met = compute(d(20), held(*all))
        assertThat(met.daysRemaining).isZero()
        assertThat(met.requirementMet).isTrue()
        assertThat(met.proposalNotStated).isEqualTo(ReserveAveraging.PERIOD_OVER)

        val short = compute(d(10), held(*all.map { it.first to "99" }.toTypedArray()))
        assertThat(short.requirementMet).isFalse()

        val gappy = compute(d(10), held(*all.drop(1).toTypedArray()))
        assertThat(gappy.requirementMet).isNull()
    }

    @Test
    fun `snapshots outside the elapsed part of the period are ignored`() {
        val r = compute(d(2), held(1 to "100", 2 to "100", 3 to "999"))
        assertThat(r.daysWithData).isEqualTo(2)
        assertThat(r.averageHoldings).isEqualByComparingTo("100")
    }

    @Test
    fun `calendar rejects overlapping periods and a base date after the period`() {
        assertThatThrownBy {
            MaintenanceCalendar("c", "1", CalendarStatus.VERIFIED, "s", listOf(period, period.copy(id = "Q")))
        }.hasMessageContaining("overlap")
        assertThatThrownBy { MaintenancePeriod("X", d(1), d(2), d(3)) }.hasMessageContaining("base reference")
    }

    @Test
    fun `shipped calendar is labelled SAMPLE-UNVERIFIED and covers 2026 without gaps`() {
        val url = requireNotNull(javaClass.classLoader.getResource("application.yaml"))
        val config = SmallRyeConfigBuilder().withSources(YamlConfigSource(url))
            .withMapping(MinReservesConfig::class.java).build()
        val calendar = config.getConfigMapping(MinReservesConfig::class.java).toCalendar()

        assertThat(calendar.status).isEqualTo(CalendarStatus.SAMPLE_UNVERIFIED)
        assertThat(calendar.source).contains("SAMPLE / UNVERIFIED")
        assertThat(calendar.periods).hasSize(12)
        calendar.periods.zipWithNext().forEach { (a, b) -> assertThat(b.start).isEqualTo(a.end.plusDays(1)) }
        assertThat(calendar.resolve(LocalDate.parse("2026-09-15"))?.id).isEqualTo("2026-09")
    }
}
