// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.warmup.StartupWarmup
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

/**
 * A warm-up that never finishes must not wedge readiness (#11890): the same parked contributor
 * as [WarmupReadinessGateIT], with a short cap. Readiness turns UP once the cap elapses although
 * the warm-up is still running, and `openbank_warmup_cap_exceeded_total` records that it did.
 */
@QuarkusTest
@TestProfile(WarmupCapIT.CapProfile::class)
@QuarkusTestResource(WarmupReadinessGateIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class WarmupCapIT {

    class CapProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.warmup.enabled" to "true",
            "openbank.warmup.test-hold" to "true",
            "openbank.warmup.max-duration" to "3s",
        )
    }

    @Inject
    lateinit var warmup: StartupWarmup

    @Inject
    lateinit var registry: MeterRegistry

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Test
    fun `a warm-up that never finishes opens readiness at the cap and counts it`() {
        try {
            assertThat(WarmupReadinessGateIT.HoldingContributor.entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            var status = 0
            while (System.nanoTime() < deadline) {
                status = readyStatus()
                if (status == OK) break
                Thread.sleep(POLL_MILLIS)
            }
            assertThat(status).describedAs("readiness after the cap").isEqualTo(OK)
            assertThat(warmup.gate.isFinished).describedAs("warm-up still parked").isFalse()
            assertThat(registry.counter("openbank.warmup.cap.exceeded").count()).isEqualTo(1.0)
        } finally {
            WarmupReadinessGateIT.HoldingContributor.release.countDown()
        }
    }

    private fun readyStatus(): Int = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:$testPort/q/health/ready")).GET().build(),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    private companion object {
        const val OK = 200
        const val WAIT_SECONDS = 60L
        const val POLL_MILLIS = 100L
    }
}
