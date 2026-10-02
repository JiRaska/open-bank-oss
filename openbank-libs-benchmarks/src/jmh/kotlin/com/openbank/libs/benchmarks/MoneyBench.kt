// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.benchmarks

import com.openbank.libs.domain.money.Money
import com.openbank.libs.domain.money.RoundingPolicy
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class MoneyBench {
    private val amount = BigDecimal("1234.56")
    private val a = Money.of("1234.56", "EUR")
    private val b = Money.of("0.44", "EUR")
    private val total = Money.of("100.00", "EUR")

    @Benchmark
    fun of(): Money = Money.of(amount, "EUR")

    @Benchmark
    fun plus(): Money = a + b

    @Benchmark
    fun round(): Money = a.round(RoundingPolicy.MONEY_SCALE)

    @Benchmark
    fun split(): List<Money> = total.split(SPLIT_PARTS)

    @Benchmark
    fun allocate(): List<Money> = total.allocate(RATIO_A, RATIO_B, RATIO_C)

    private companion object {
        const val SPLIT_PARTS = 3
        const val RATIO_A = 50L
        const val RATIO_B = 30L
        const val RATIO_C = 20L
    }
}
