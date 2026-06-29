// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable

/**
 * Boot smoke test guarding the "released-but-never-booted" defect class.
 *
 * swift-service is a released component (version.txt) with no GitOps deployment and previously had
 * zero @QuarkusTest, so boot/config defects could only surface in production. The same class bit
 * psd2-service (#1163 missing runtime DB extensions, #1170 a duplicate YAML key dropping the HTTP
 * port). This IT boots the full app on a Testcontainers Postgres + Valkey, runs Flyway, and asserts
 * the readiness probe is UP and the service-info endpoint answers — the two signals that prove the
 * wiring, config and migrations survive a real boot. Mirrors clearing/interest/sdd's per-job
 * Testcontainers IT (issue #578).
 *
 * The lone `@Channel("swift-events-out")` Kafka emitter is swapped to the in-memory connector so no
 * broker is needed and the readiness probe carries no Kafka health check.
 */
// CI-skipped (#2404): root cause — Quarkus's JUnit5 BeforeAllCallback fires BEFORE JUnit5 evaluates
// @DisabledIfEnvironmentVariable, so Quarkus boots + Testcontainers (Postgres + Valkey) starts in CI
// despite this annotation. The containers hang on CI runners → 45-min job timeout. The REAL guard is
// the Gradle-level `exclude("**/SwiftBootSmokeIT*")` in build.gradle.kts (evaluated at task config
// time, before JUnit5 discovery). This annotation remains as a defence-in-depth fallback. Re-enable
// per #2404 (runner-side investigation needed).
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
@QuarkusTest
@QuarkusTestResource(SwiftBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.swift.it.PostgresRedisTestResource::class)
class SwiftBootSmokeIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("swift-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `the service-info endpoint answers on the configured HTTP port`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-swift-service")
    }
}
