// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.benchmarks

import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.Amortization
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.Aprc
import com.openbank.libs.lending.RepaymentSchedule
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private const val WORKLOAD_FIRST_DUE_YEAR = 2026
private const val WORKLOAD_FIRST_DUE_DAY = 31
private const val MONTHLY_PERIODS_PER_YEAR = 12
private const val SHORT_MONTHLY_TERM = 120
private const val GOLDEN_MONTHLY_TERM = 360
private const val LONG_MONTHLY_TERM = 480
private const val SMALL_PRINCIPAL_ANNUAL_TERM = 191

/** Bounded lending workloads for #13448; case sizes are measurements, not accepted term limits. */
// Eleven independently named JMH workloads plus trial setup and fixture validation helpers.
// Keeping their identities together preserves the measured workload and baseline mapping.
@Suppress("TooManyFunctions")
@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class LendingBench {
    private data class ScheduleInput(
        val principal: Money,
        val rate: BigDecimal,
        val periods: Int,
        val perYear: Int,
        val firstDueDate: LocalDate = LocalDate.of(WORKLOAD_FIRST_DUE_YEAR, 1, WORKLOAD_FIRST_DUE_DAY),
    ) {
        fun schedule(): RepaymentSchedule = Amortization.schedule(
            principal = principal,
            nominalAnnualRate = rate,
            termPeriods = periods,
            firstDueDate = firstDueDate,
            periodsPerYear = perYear,
            method = AmortizationMethod.ANNUITY,
        )
    }

    private data class AprcInput(val advances: List<Aprc.CashFlow>, val payments: List<Aprc.CashFlow>) {
        fun solve(): BigDecimal? = Aprc.solve(advances, payments)
    }

    private lateinit var monthly120: ScheduleInput
    private lateinit var monthly360: ScheduleInput
    private lateinit var monthly480: ScheduleInput
    private lateinit var annual191: ScheduleInput
    private lateinit var aprc120: AprcInput
    private lateinit var aprc360: AprcInput
    private lateinit var aprc480: AprcInput
    private lateinit var tinyAnnual: AprcInput
    private lateinit var annualTie: AprcInput
    private lateinit var nonannualTwoYear: AprcInput
    private lateinit var mixedUnique: AprcInput

    @Setup(Level.Trial)
    fun prepare() {
        // Ordinary positive-rate seed, expanded to the existing intake/catalog workload sizes.
        monthly120 =
            ScheduleInput(
                Money.of("100000.00", "CZK"),
                BigDecimal("0.069"),
                SHORT_MONTHLY_TERM,
                MONTHLY_PERIODS_PER_YEAR,
            )
        monthly480 =
            ScheduleInput(
                Money.of("100000.00", "CZK"),
                BigDecimal("0.069"),
                LONG_MONTHLY_TERM,
                MONTHLY_PERIODS_PER_YEAR,
            )
        // Exact input from AmortizationGoldenTest; its committed golden remains unchanged.
        monthly360 =
            ScheduleInput(
                Money.of("1234567.89", "CZK"),
                BigDecimal("0.0425"),
                GOLDEN_MONTHLY_TERM,
                MONTHLY_PERIODS_PER_YEAR,
            )
        // Exact annual small-principal regression from AmortizationSmallPrincipalTest.
        annual191 = ScheduleInput(Money.of("3680.655", "KWD"), BigDecimal("0.3000"), SMALL_PRINCIPAL_ANNUAL_TERM, 1)

        val schedule120 = checkedSchedule(monthly120)
        val schedule360 = checkedSchedule(monthly360)
        val schedule480 = checkedSchedule(monthly480)
        checkedSchedule(annual191)
        // Schedule generation and cash-flow conversion are outside the timed APRC operation.
        aprc120 = aprcInput(monthly120, schedule120)
        aprc360 = aprcInput(monthly360, schedule360)
        aprc480 = aprcInput(monthly480, schedule480)
        for (input in listOf(aprc120, aprc360, aprc480)) checkNotNull(input.solve())
        // Parse the permanent regression inputs outside every timed operation.
        tinyAnnual = AprcInput(
            listOf(Aprc.CashFlow(BigDecimal.ZERO, BigDecimal("0.010"))),
            listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("0.011"))),
        )
        annualTie = AprcInput(
            listOf(Aprc.CashFlow(BigDecimal.ZERO, BigDecimal("10000"))),
            listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("11000.005"))),
        )
        nonannualTwoYear = AprcInput(
            listOf(Aprc.CashFlow(BigDecimal.ZERO, BigDecimal("100"))),
            listOf(Aprc.CashFlow(BigDecimal("2"), BigDecimal("121"))),
        )
        mixedUnique = AprcInput(
            listOf(
                Aprc.CashFlow(BigDecimal.ZERO, BigDecimal("100")),
                Aprc.CashFlow(BigDecimal("2"), BigDecimal("16500")),
            ),
            listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("15110"))),
        )
    }

    // Returning the actual newly computed value makes JMH consume it; no cached result is timed.
    @Benchmark
    fun scheduleMonthly120(): RepaymentSchedule = monthly120.schedule()

    @Benchmark
    fun scheduleMonthly360Golden(): RepaymentSchedule = monthly360.schedule()

    @Benchmark
    fun scheduleMonthly480Catalog(): RepaymentSchedule = monthly480.schedule()

    @Benchmark
    fun scheduleAnnual191Fallback(): RepaymentSchedule = annual191.schedule()

    @Benchmark
    fun aprcMonthly120(): BigDecimal? = aprc120.solve()

    @Benchmark
    fun aprcMonthly360(): BigDecimal? = aprc360.solve()

    @Benchmark
    fun aprcMonthly480(): BigDecimal? = aprc480.solve()

    @Benchmark
    fun aprcTinyAnnual(): BigDecimal? = tinyAnnual.solve()

    @Benchmark
    fun aprcAnnualHalfUpTie(): BigDecimal? = annualTie.solve()

    @Benchmark
    fun aprcNonannualTwoYear(): BigDecimal? = nonannualTwoYear.solve()

    @Benchmark
    fun aprcMixedUniqueInRange(): BigDecimal? = mixedUnique.solve()

    private fun checkedSchedule(input: ScheduleInput): RepaymentSchedule = input.schedule().also { schedule ->
        check(schedule.installments.size == input.periods)
        check(schedule.installments.last().closingBalance.isZero())
        check(schedule.totalPrincipal == input.principal)
        check(schedule.installments.all { it.principal.isNonNegative() && it.payment.isNonNegative() })
    }

    private fun aprcInput(input: ScheduleInput, schedule: RepaymentSchedule): AprcInput {
        check(input.perYear == MONTHLY_PERIODS_PER_YEAR)
        // Existing AprcTest fee convention: the customer receives principal less an upfront fee.
        val advances = listOf(Aprc.CashFlow(BigDecimal.ZERO, input.principal.amount - BigDecimal("2000")))
        val payments = schedule.installments.map { installment ->
            val years = BigDecimal(
                installment.number,
            ).divide(BigDecimal(MONTHLY_PERIODS_PER_YEAR), MathContext.DECIMAL128)
            Aprc.CashFlow(years, installment.payment.amount)
        }
        return AprcInput(advances, payments)
    }
}
