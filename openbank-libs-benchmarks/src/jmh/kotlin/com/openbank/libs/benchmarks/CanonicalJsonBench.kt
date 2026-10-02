// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.benchmarks

import com.openbank.libs.audit.CanonicalJson
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
class CanonicalJsonBench {
    private val document: Map<String, Any?> = mapOf(
        "eventId" to "00000000-0000-0000-0000-000000000001",
        "operation" to "APPROVE",
        "seq" to SEQ,
        "amount" to BigDecimal("1200.50"),
        "actChain" to listOf("agent-a", "agent-b"),
        "resourceId" to null,
        "payload" to mapOf("currency" to "EUR", "note" to "line one\nline \"two\"", "flags" to listOf(true, false)),
    )

    @Benchmark
    fun write(): String = CanonicalJson.write(document)

    private companion object {
        const val SEQ = 42L
    }
}
