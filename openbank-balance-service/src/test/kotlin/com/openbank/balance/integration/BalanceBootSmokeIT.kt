// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.integration

import com.openbank.balance.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boot smoke test guarding the "released-but-never-booted" defect class (issue #2471).
 *
 * balance-service has Postgres (Flyway V1..V8), a Redpanda broker (balance-outbox-out
 * dispatcher + ledger-events-in / balance-init-in consumers), and OTel, so CDI wiring is non-trivial.
 * PostgresRedpandaTestResource spins up isolated containers so no shared infra is needed.
 * The two assertions mirror every other boot smoke in the fleet: readiness probe UP + service-info
 * endpoint reachable (proves the HTTP port config and openbank-libs ServiceInfoResource wiring).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class BalanceBootSmokeIT {

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `the service-info endpoint answers on the configured HTTP port`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-balance-service")
    }
}
