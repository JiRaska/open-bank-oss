// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepainstant.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boot smoke test guarding the "released-but-never-booted" defect class.
 *
 * sepa-instant is a released money-path component with Flyway migrations, Redis idempotency,
 * Kafka events and — since ADR-0104 D4 (#1732) — a `ClearingSimulatorClient` REST client. Any
 * mis-wired CDI bean, missing config key or duplicate YAML key would surface here before
 * reaching the sandbox. Mirrors swift/domestic/pid/tpp patterns (issue #578 sweep).
 *
 * The Kafka outgoing channel is swapped to the in-memory connector so no broker is required.
 */
@QuarkusTest
@QuarkusTestResource(SctInstBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sepainstant.it.PostgresRedpandaRedisTestResource::class)
class SctInstBootSmokeIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("sct-inst-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `the service-info endpoint answers on the configured HTTP port`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-sepa-instant")
    }
}
