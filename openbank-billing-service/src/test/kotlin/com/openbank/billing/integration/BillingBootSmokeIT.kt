// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
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
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class BillingBootSmokeIT {

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
