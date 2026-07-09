// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.integration

import com.openbank.anacredit.it.PostgresRedpandaTestResource
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
 * anacredit-service is a released component (version.txt) with no GitOps deployment. As of the
 * `loan.stage_changed` event-ingestion follow-up (ADR-0037, issue #638) it now boots a real Postgres
 * datasource (Flyway `V1__create_loan_stage_projection.sql`) and a Kafka consumer
 * (`@Incoming("lending-events-in")`), so this mirrors `balance-service`/`party-service`'s
 * Testcontainers-backed boot smoke test rather than the old bare-`@QuarkusTest` version (which only
 * covered the in-memory `CreditExposure` feed). The two assertions prove the wiring, config, DB
 * migration and OIDC override all survive a real boot against live infrastructure.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class AnaCreditBootSmokeIT {

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `the service-info endpoint answers on the configured HTTP port`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) })
            .extract().body().asString()
        assertThat(body).contains("openbank-anacredit-service")
    }
}
