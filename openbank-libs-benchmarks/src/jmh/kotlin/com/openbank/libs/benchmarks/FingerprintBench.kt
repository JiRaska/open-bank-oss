// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.benchmarks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.libs.idempotency.RequestFingerprint
import com.openbank.libs.idempotency.RequestFingerprints
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/** The idempotency fingerprint every mutating request pays for (SHA-256 + hex of the body). */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class FingerprintBench {
    data class PaymentRequest(
        val debtorIban: String,
        val creditorIban: String,
        val creditorName: String,
        val amount: BigDecimal,
        val currency: String,
        val remittance: String,
    )

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val body300 = jsonBody(BODY_SMALL)
    private val body4k = jsonBody(BODY_LARGE)
    private val dto = PaymentRequest(
        debtorIban = "DE89370400440532013000",
        creditorIban = "FR1420041010050500013M02606",
        creditorName = "Bob Creditor",
        amount = BigDecimal("12.34"),
        currency = "EUR",
        remittance = "Invoice 2026-0042",
    )

    @Benchmark
    fun of300B(): String = RequestFingerprint.of("POST", PATH, body300)

    @Benchmark
    fun of4KB(): String = RequestFingerprint.of("POST", PATH, body4k)

    @Benchmark
    fun ofDto(): String = RequestFingerprints.of(mapper, "POST", PATH, dto)

    private companion object {
        const val PATH = "/api/v1/payments"
        const val BODY_SMALL = 300
        const val BODY_LARGE = 4096

        /** A JSON object of exactly [size] ASCII bytes; content is fixed, so the run is repeatable. */
        fun jsonBody(size: Int): String {
            val prefix = "{\"remittance\":\""
            val suffix = "\"}"
            return prefix + "x".repeat(size - prefix.length - suffix.length) + suffix
        }
    }
}
