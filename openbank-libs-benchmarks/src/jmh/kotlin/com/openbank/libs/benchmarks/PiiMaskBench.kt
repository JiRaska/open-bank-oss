// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.benchmarks

import com.openbank.libs.security.PiiMask
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class PiiMaskBench {
    private val emailInput = "john.doe@example.com"
    private val ibanInput = "CZ65 0800 0000 1920 0014 5399"
    private val panInput = "4532-0151-1283-0366"

    @Benchmark
    fun email(): String = PiiMask.email(emailInput)

    @Benchmark
    fun iban(): String = PiiMask.iban(ibanInput)

    @Benchmark
    fun pan(): String = PiiMask.pan(panInput)
}
