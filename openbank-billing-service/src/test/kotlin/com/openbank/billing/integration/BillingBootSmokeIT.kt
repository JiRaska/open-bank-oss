// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.it.PostgresRedisTestResource
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
 * Boot smoke test — guards the "released-but-never-booted" defect class (ADR-0114): boots the
 * full CDI container (validating the use case + persistence + outbox + ledger-adapter +
 * scheduler + four-eyes wiring that unit tests do not exercise) on Testcontainers Postgres +
 * Valkey, runs Flyway, and checks the readiness probe and the libs-served service-info endpoint.
 *
 * Now needs real infrastructure (ADR-0143 phase 2c added the datastore/outbox/redis), so this
 * moved from the phase-2b `@QuarkusTest`-only `BillingBootSmokeTest` to the fleet's
 * `*BootSmokeIT` + `PostgresRedisTestResource` convention (mirrors
 * `openbank-standing-order-service`/`openbank-settlement-service`).
 *
 * `InMemoryKafkaResource` switches `billing-events-out` (ADR-0248, billing's first Kafka
 * publisher) to the in-memory connector: `PostgresRedisTestResource` provisions no Kafka broker,
 * so without this the SmallRye Kafka producer's own readiness check reports the channel `[KO]`
 * and `/q/health/ready` answers 503 — exactly the `StandingOrderBootSmokeIT` pattern for its
 * `standing-order-events-out`/`standing-order-due-in` channels.
 */
@QuarkusTest
@QuarkusTestResource(BillingBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class BillingBootSmokeIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("billing-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        val body = (Given { this } When { get("/q/health/ready") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("UP")
    }

    @Test
    fun `the service-info endpoint answers with this service's identity`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-billing-service")
    }
}
