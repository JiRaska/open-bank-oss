// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep

import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test

/**
 * Boot smoke test — guards the "released-but-never-booted" defect class: it boots the full CDI
 * container (validating the use case + REST-client adapter wiring that unit tests do not exercise)
 * and checks the readiness probe and the libs-served service-info endpoint. No datastore, so no
 * Testcontainers infrastructure is needed (finrep-service is stateless, reads ledger-service via REST).
 */
@QuarkusTest
class FinrepBootSmokeTest {

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        val body = (Given { this } When { get("/q/health/ready") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("UP")
    }

    @Test
    fun `the service-info endpoint answers with this service's identity`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-finrep-service")
    }

    @Test
    fun `dependency application yaml contributes defaults and service yaml still wins conflicts`() {
        val config = ConfigProvider.getConfig()

        assertThat(config.getValue("quarkus.http.header.Strict-Transport-Security.value", String::class.java))
            .isEqualTo("max-age=31536000; includeSubDomains")
        assertThat(
            config.getValue(
                "quarkus.http.header.X-Frame-Options.value",
                String::class.java,
            ),
        ).isEqualTo("SAMEORIGIN")
        assertThat(config.getValue("quarkus.management.port", Int::class.javaObjectType))
            .isEqualTo(8085)
        assertThat(config.getValue("quarkus.management.root-path", String::class.java))
            .isEqualTo("/q")
        assertThat(config.getValue("quarkus.management.enabled", Boolean::class.javaObjectType))
            .isFalse()
        assertThat(config.getValue("openbank.service.port", Int::class.javaObjectType))
            .isEqualTo(8140)
    }
}
