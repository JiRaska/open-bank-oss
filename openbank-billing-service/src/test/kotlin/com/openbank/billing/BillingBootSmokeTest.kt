// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boot smoke test — guards the "released-but-never-booted" defect class: it boots the full CDI
 * container (validating the use case + stub adapter wiring that unit tests do not exercise) and
 * checks the readiness probe and the libs-served service-info endpoint. No datastore yet, so no
 * Testcontainers infrastructure is needed (ADR-0143 phase 2b).
 */
@QuarkusTest
class BillingBootSmokeTest {

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
