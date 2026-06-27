// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boot smoke-test guarding the "released-but-never-booted" defect class.
 *
 * settlement-service is a released component (version.txt) with Hibernate Reactive + Flyway
 * and two REST clients (balance-api, ledger-api). The %test profile in application.yaml points
 * both REST clients at localhost (they are not called during a health check), so no WireMock is
 * needed. A Testcontainers Postgres provides a real DB for Flyway migration on boot.
 * This mirrors the pattern established for pid-service (#1194) and tpp-service (#1195).
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.settlement.it.PostgresTestResource::class)
class SettlementBootSmokeIT {

    @Test
    fun `the app boots and the liveness probe reports UP`() {
        Given { this } When { get("/q/health/live") } Then { statusCode(200) }
    }

    @Test
    fun `the readiness probe reports UP — Flyway migrations ran cleanly`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `the service-info endpoint answers on the configured HTTP port`() {
        val body = (
            Given { this } When { get("/api/v1/info") } Then { statusCode(200) }
            ).extract().body().asString()
        assertThat(body).contains("openbank-settlement-service")
    }
}
