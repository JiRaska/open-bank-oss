// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boot smoke test guarding the "released-but-never-booted" defect class.
 *
 * psd2-service shipped two latent defects that only a real Quarkus boot against a real DB could
 * catch, because the service has no GitOps deployment and previously had no @QuarkusTest:
 *   - #1163: the runtime DB extensions (reactive-pg-client / flyway / jdbc-postgresql) were missing,
 *     so the app CrashLooped with "No reactive SQL client implementation" the moment it booted.
 *   - #1170: a duplicate `quarkus.http:` key in application.yaml silently dropped `port: 8107`, so
 *     the service bound 8080 instead of its assigned port.
 *
 * Neither is reachable from unit tests (no boot, no DB). This IT boots the full app on the
 * Testcontainers Postgres + Valkey, runs Flyway, and asserts the readiness probe is UP and the
 * service-info endpoint answers — the two signals that prove the wiring, config and migrations all
 * survive a real boot. Mirrors clearing/interest's per-job Testcontainers IT (issue #578).
 *
 * No Kafka in-memory swap is needed since #8510: the lone `psd2-events-out` emitter was deleted
 * with the dead outbox apparatus, so the service declares no outgoing channel at all.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.psd2.it.PostgresRedisTestResource::class)
class Psd2BootSmokeIT {

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `the service-info endpoint answers on the configured HTTP port`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-psd2-service")
    }
}
