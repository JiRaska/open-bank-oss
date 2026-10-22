// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.benchmarks

import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.BalanceSample
import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.Posting
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductEvent
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * ADR-0352 §8: p99 < 2 ms per `evaluate` over the golden set. SampleTime mode so the JSON result
 * carries `scorePercentiles` (the gate reads p99 from it, see `check-bench-alloc-baseline.py`
 * `latency_bounds`); the gc profiler still records B/op for the allocation baseline. One
 * invocation evaluates every step of every golden scenario against its initial state, so the
 * per-op figure is an upper bound on a single evaluation.
 *
 * The scenarios are read with the domain's own reader (only the fields an evaluation needs); the
 * full runner lives in openbank-libs-testing, which is a test-fixture library and cannot be on this
 * classpath.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ProductInterpreterBench {
    private data class Case(
        val revision: ProductRevisionIr,
        val state: ProductState,
        val event: ProductEvent,
        val clock: ProductClock,
    )

    private val interpreter = ProductInterpreter(FinanceProductArithmetic)
    private val cases: List<Case> = text("index.txt").lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .flatMap { cases(text(it)) }

    @Benchmark
    fun evaluateGoldenSet(): Int {
        var n = 0
        for (c in cases) n += interpreter.evaluate(c.revision, c.state, c.event, c.clock).size
        return n
    }

    private companion object {
        fun text(name: String): String =
            checkNotNull(ProductInterpreterBench::class.java.getResourceAsStream("/product-golden/$name")) { name }
                .readBytes().toString(Charsets.UTF_8)

        fun dec(v: Any?): BigDecimal = when (v) {
            is BigDecimal -> v
            is String -> BigDecimal(v)
            else -> BigDecimal.ZERO
        }

        @Suppress("UNCHECKED_CAST")
        fun cases(json: String): List<Case> {
            val root = IrJson.parse(json) as Map<String, Any?>
            val revision = ProductIrParser.fromTree(root["revision"])
            val s = root["state"] as Map<String, Any?>
            val state = ProductState(
                currency = s["currency"] as String,
                balance = dec(s["balance"]),
                accruedInterest = dec(s["accruedInterest"]),
                openedOn = LocalDate.parse(s["openedOn"] as String),
                balanceSamples = (s["balanceSamples"] as? List<Map<String, Any?>>).orEmpty()
                    .map { BalanceSample(LocalDate.parse(it["on"] as String), dec(it["balance"])) },
            )
            return (root["steps"] as List<Map<String, Any?>>).map { step ->
                val posting = (step["posting"] as? Map<String, Any?>)?.let {
                    Posting(
                        EventKind.valueOf(it["kind"] as String),
                        PostingDirection.valueOf(it["direction"] as String),
                        dec(it["amount"]),
                    )
                }
                val event = ProductEvent(
                    hook = ProductIrParser.HOOK_KEYS.getValue(step["hook"] as String),
                    tag = step["tag"] as? String,
                    posting = posting,
                )
                Case(revision, state, event, ProductClock(Instant.parse(step["at"] as String), ZoneOffset.UTC))
            }
        }
    }
}
