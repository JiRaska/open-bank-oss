// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.integration

import com.openbank.anacredit.it.PostgresTestResource
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
 * anacredit-service is a released component (version.txt) with no GitOps deployment. As of ADR-0037
 * v2 it is Postgres-backed (reactive Panache + Flyway), so this IT now boots the full application
 * against a real Testcontainers PostgreSQL — catching the defect class unit tests cannot see:
 * missing driver, a duplicate YAML config key, a bad migration, or missing CDI beans. Mirrors
 * openbank-product-catalog's `ProductCatalogBootSmokeIT`. The two assertions prove the wiring,
 * config, Flyway migration and OIDC override survive a real boot.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
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
