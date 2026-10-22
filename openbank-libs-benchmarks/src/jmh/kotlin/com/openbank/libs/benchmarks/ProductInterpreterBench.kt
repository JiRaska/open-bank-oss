// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.benchmarks

import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductEvent
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import com.openbank.libs.testing.product.GoldenScenarios
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import java.util.concurrent.TimeUnit

/**
 * ADR-0352 §8: p99 < 2 ms per `evaluate` over the golden set. SampleTime mode so the JSON result
 * carries `scorePercentiles` (the gate reads p99 from it, see `check-bench-alloc-baseline.py`
 * `latency_bounds`); the gc profiler still records B/op for the allocation baseline. One
 * invocation evaluates every step of every golden scenario, so the per-op figure is an upper
 * bound on a single evaluation.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ProductInterpreterBench {
    private data class Case(val revision: ProductRevisionIr, val state: ProductState, val event: ProductEvent, val clock: ProductClock)

    private val interpreter = ProductInterpreter(FinanceProductArithmetic)
    private val cases: List<Case> = GoldenScenarios.all().flatMap { s ->
        s.steps.map { step -> Case(s.revision, s.initialState, step.event, ProductClock(step.at, s.zone)) }
    }

    @Benchmark
    fun evaluateGoldenSet(): Int {
        var n = 0
        for (c in cases) {
            val out: List<Instruction> = interpreter.evaluate(c.revision, c.state, c.event, c.clock)
            n += out.size
        }
        return n
    }
}
