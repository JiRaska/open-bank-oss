// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.integration

import com.openbank.consent.it.ConsentPostgresRedisTestResource
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
 * Boot smoke test guarding the "released-but-never-booted" defect class (issue #2469).
 *
 * consent-service is a released money-path component (version.txt) that uses Hibernate Reactive
 * (Postgres + Flyway), Redis (idempotency), and a Kafka outgoing emitter (`consent-events-out`).
 * Boot/config defects — duplicate YAML keys, missing CDI beans, OIDC mis-wiring — only surface
 * at first boot, not in unit tests. The two assertions prove that config, Flyway migrations,
 * and openbank-libs wiring survive a real Quarkus boot.
 *
 * The `consent-events-out` emitter is switched to in-memory so no Kafka broker is required.
 * Postgres + Redis are spun up in isolated Testcontainers by [ConsentPostgresRedisTestResource].
 */
@QuarkusTest
@QuarkusTestResource(ConsentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
class ConsentBootSmokeIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("consent-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `the service-info endpoint answers on the configured HTTP port`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) })
            .extract().body().asString()
        assertThat(body).contains("openbank-consent-service")
    }
}
