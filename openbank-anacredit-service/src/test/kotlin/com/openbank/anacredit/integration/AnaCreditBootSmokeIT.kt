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
 * anacredit-service is a released component (version.txt) with no GitOps deployment. It now boots
 * real infrastructure on two independent fronts: ADR-0037 v2 made `CreditExposure` Postgres-backed
 * (reactive Panache + Flyway `V2__create_credit_exposures.sql`), and the `loan.stage_changed`
 * event-ingestion follow-up (issue #638) added a Kafka consumer (`@Incoming("lending-events-in")`)
 * plus its own Postgres-backed `loan_stage_projection` table (`V1__create_loan_stage_projection.sql`).
 * `PostgresRedpandaTestResource` covers both — Postgres and Kafka/Redpanda — so this single IT boots
 * the full application against real Testcontainers infra, catching the defect class unit tests
 * cannot see: missing driver, a duplicate YAML config key, a bad migration, or missing CDI beans.
 * Mirrors `openbank-product-catalog`'s `ProductCatalogBootSmokeIT` and `balance-service`/
 * `party-service`'s Testcontainers-backed boot smoke tests. The two assertions prove the wiring,
 * config, both Flyway migrations, the Kafka consumer, and the OIDC override all survive a real boot.
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
