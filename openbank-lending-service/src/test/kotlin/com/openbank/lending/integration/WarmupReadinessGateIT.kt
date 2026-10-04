// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.warmup.StartupWarmup
import com.openbank.libs.warmup.WarmupContributor
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The libs-runtime startup warm-up's readiness gate, against the real running app (#11890).
 *
 * A test-only [WarmupContributor] parks the warm-up thread on a latch, which makes the window
 * between "HTTP is serving" and "warm-up finished" as long as the test needs, so the assertions
 * are about the gate and not about a race:
 *  - while the warm-up is running, `/q/health/ready` answers 503 with `startup-warmup` DOWN,
 *    even though the same server already answers requests;
 *  - releasing the latch lets the warm-up finish, and readiness turns UP;
 *  - the generic steps (resource types, Hibernate entities, authz) ran and were timed into
 *    `openbank_warmup_seconds`.
 * The never-finishing case (cap elapses -> UP anyway, counted) is [WarmupCapIT].
 */
@QuarkusTest
@TestProfile(WarmupReadinessGateIT.HoldProfile::class)
@QuarkusTestResource(WarmupReadinessGateIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class WarmupReadinessGateIT {

    class HoldProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.warmup.enabled" to "true",
            "openbank.warmup.test-hold" to "true",
            // Far above the test's own waits, so the cap cannot open the gate under the assertions.
            "openbank.warmup.max-duration" to "300s",
        )
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("lending-events-out").toMutableMap()
            props["quarkus.kafka.devservices.enabled"] = "false"
            props["openbank.outbox.dispatch-enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var warmup: StartupWarmup

    @Inject
    lateinit var registry: MeterRegistry

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Test
    fun `readiness stays DOWN while warm-up runs and turns UP when it finishes`() {
        try {
            assertThat(HoldingContributor.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .describedAs("warm-up reached the contributor step").isTrue()

            val held = get("/q/health/ready")
            assertThat(held.statusCode()).isEqualTo(SERVICE_UNAVAILABLE)
            assertThat(held.body()).contains("startup-warmup").contains("DOWN")
            // The server is up and serving; only readiness holds traffic back.
            assertThat(get("/q/health/live").statusCode()).isEqualTo(OK)
            assertThat(warmup.gate.isFinished).isFalse()
        } finally {
            HoldingContributor.release.countDown()
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!warmup.gate.isFinished && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)
        val ready = get("/q/health/ready")
        assertThat(ready.statusCode()).describedAs(ready.body()).isEqualTo(OK)

        val steps = registry.find("openbank.warmup").timers().map { it.id.getTag("step") }
        assertThat(steps).contains("resource-types", "hibernate-entities", "authz", "contributor:test-hold", "total")
        assertThat(registry.find("openbank.warmup").tag("step", "hibernate-entities").tag("outcome", "ok").timer())
            .describedAs("one-row reads of every entity succeeded").isNotNull()
    }

    private fun get(path: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:$testPort$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /** Parks the warm-up thread only when the profile asks; a no-op for every other test class. */
    @ApplicationScoped
    class HoldingContributor(
        @ConfigProperty(name = "openbank.warmup.test-hold", defaultValue = "false") private val hold: Boolean,
    ) : WarmupContributor {
        override val name = "test-hold"

        override fun warm(): String {
            if (!hold) return "not holding"
            entered.countDown()
            release.await(HOLD_SECONDS, TimeUnit.SECONDS)
            return "held"
        }

        companion object {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            private const val HOLD_SECONDS = 600L
        }
    }

    private companion object {
        const val OK = 200
        const val SERVICE_UNAVAILABLE = 503
        const val WAIT_SECONDS = 60L
        const val POLL_MILLIS = 100L
    }
}
