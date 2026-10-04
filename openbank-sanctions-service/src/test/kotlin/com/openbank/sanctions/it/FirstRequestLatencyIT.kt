// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sanctions.it

import com.openbank.libs.warmup.StartupWarmup
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * The first request after a pod starts used to take ~1.9 s against 17-60 ms for every later one
 * (#11890). These two classes time that first authenticated `GET /api/v1/sanctions/pending` with
 * the libs-runtime startup warm-up on and off. Like the deployed path, each first waits for the
 * readiness gate (immediately open when disabled) — that is when Argo Rollouts sends traffic.
 *
 * Timing is reported, not asserted: a wall-clock bound would be a flaky test. That makes these a
 * MEASUREMENT, not a regression guard, so they are opt-in — each costs a Quarkus restart under its
 * own profile, and the default suite's heap is not sized for two more. Run each class in its own
 * Gradle invocation (in a shared JVM the second inherits the JIT state of the first):
 * `JAVA_TOOL_OPTIONS=-Dopenbank.measure.first-request=true ./gradlew
 * :openbank-sanctions-service:test --tests '*FirstRequestLatencyColdIT'` (then `...WarmIT`).
 */
const val MEASURE_PROPERTY = "openbank.measure.first-request"

abstract class FirstRequestLatencyBase(private val label: String) {

    @Inject
    lateinit var warmup: StartupWarmup

    @Test
    @EnabledIfSystemProperty(named = MEASURE_PROPERTY, matches = "true")
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `time the first authenticated request after readiness`() {
        val deadline = System.nanoTime() + READY_TIMEOUT_NANOS
        while (!warmup.gate.isReady() && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)
        assertThat(warmup.gate.isReady()).isTrue()

        val timings = (1..REQUESTS).map {
            val t0 = System.nanoTime()
            When { get("/api/v1/sanctions/pending") } Then { statusCode(200) }
            (System.nanoTime() - t0) / NANOS_PER_MILLI
        }
        println("FIRST_REQUEST_LATENCY[$label] first=${timings.first()}ms then=${timings.drop(1)}")
    }

    private companion object {
        const val REQUESTS = 5
        const val POLL_MILLIS = 50L
        const val NANOS_PER_MILLI = 1_000_000L
        const val READY_TIMEOUT_NANOS = 30_000_000_000L
    }
}

class WarmupEnabledProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = mapOf("openbank.warmup.enabled" to "true")
}

class WarmupDisabledProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = mapOf("openbank.warmup.enabled" to "false")
}

@EnabledIfSystemProperty(named = MEASURE_PROPERTY, matches = "true")
@QuarkusTest
@TestProfile(WarmupEnabledProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
class FirstRequestLatencyWarmIT : FirstRequestLatencyBase("warm-up enabled")

@EnabledIfSystemProperty(named = MEASURE_PROPERTY, matches = "true")
@QuarkusTest
@TestProfile(WarmupDisabledProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
class FirstRequestLatencyColdIT : FirstRequestLatencyBase("warm-up disabled")
