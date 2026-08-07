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

    /**
     * The base file must keep OIDC's *tenant* disabled in tests, never the extension itself.
     *
     * This assertion exists because the difference is invisible from finrep, which injects no
     * `JsonWebToken`, and catastrophic elsewhere: `quarkus.oidc.enabled=false` removes the
     * `JsonWebToken` bean, so every module whose resource takes one as a constructor parameter
     * fails CDI validation and its whole `@QuarkusTest` refuses to boot. That is what the first
     * version of this base file did to campaign-service and mcp-service, and nothing here or in CI
     * noticed — path-scoped builds do not rebuild a service whose files did not change, so a base
     * yaml edit is exactly the change that ships without their tests running.
     *
     * Asserting the absence of `enabled=false` rather than only the presence of
     * `tenant-enabled=false` is the point: the two are different properties, so a service setting
     * one does not override the other, and re-adding the hard switch here would break those modules
     * again while this file still looked correct.
     */
    @Test
    fun `the base file disables the OIDC tenant in tests, and never the extension itself`() {
        val config = ConfigProvider.getConfig()

        assertThat(config.getValue("quarkus.oidc.tenant-enabled", Boolean::class.javaObjectType))
            .describedAs("tests must not reach Keycloak")
            .isFalse()
        assertThat(config.getOptionalValue("quarkus.oidc.enabled", Boolean::class.javaObjectType).orElse(true))
            .describedAs(
                "quarkus.oidc.enabled=false removes the JsonWebToken bean fleet-wide — every service " +
                    "whose resource injects one then fails CDI validation and cannot boot its tests",
            )
            .isTrue()
    }
}
