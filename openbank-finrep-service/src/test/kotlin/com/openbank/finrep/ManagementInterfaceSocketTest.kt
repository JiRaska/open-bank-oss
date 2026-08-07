// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test

/**
 * Does the management interface actually BIND and SERVE on the config the shared base supplies —
 * or do we only know the values resolve?
 *
 * WHY THIS EXISTS
 *
 * #3686 moved `quarkus.management.*` out of this service's `application.yaml` and into
 * `openbank-libs-runtime`'s shared base. `FinrepBootSmokeTest` then asserted that
 * `quarkus.management.port`, `.root-path` and `.enabled` RESOLVE — which is a claim about config
 * lookup, not about a listening socket. #4017 relied on that to teach
 * `check-probe-port-listener.py` that 8085 is open, and #4030 disputes it and re-declares the
 * block in this service. Neither side had observed the interface actually serving.
 *
 * This test closes the half that a JVM test can close: with the interface enabled, the management
 * HTTP server binds using the base-supplied `root-path` and answers a health request. If the base
 * stopped contributing management config, or the interface stopped serving `/q/health` on it, this
 * goes red — and it cannot pass by reading a value nothing acts on.
 *
 * WHAT IT DELIBERATELY DOES NOT PROVE, so nobody reads more into a green than is there:
 *
 *  - **That production gets `enabled: true` from the base.** This service's own `%test` block sets
 *    `quarkus.management.enabled: false`, so a JVM test must override it to observe anything at
 *    all. The profile below overrides ONLY `enabled`, and deliberately leaves `port` and
 *    `root-path` to come from the base — so a green here does say the base reaches the bound
 *    interface, and does not say the base's `enabled` survives augmentation in the packaged
 *    fast-jar. That question is answerable only by booting the built artifact (a
 *    `@QuarkusIntegrationTest`, or the in-image probe `check-probe-port-listener.py` performs).
 *  - **The literal port 8085.** `@QuarkusTest` assigns a management TEST port, so asserting 8085
 *    here would assert Quarkus's test-port machinery, not the deployed configuration. The port is
 *    read from config at request time instead.
 */
@QuarkusTest
@TestProfile(ManagementInterfaceSocketTest.ManagementEnabled::class)
class ManagementInterfaceSocketTest {

    /**
     * Re-enables the management interface, and nothing else. Literals only: a [QuarkusTestProfile]
     * is loaded in a different classloader from the test class, so anything computed here would be
     * computed twice and the two copies need not agree.
     */
    class ManagementEnabled : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.management.enabled" to "true",
        )
    }

    @Test
    fun `the management interface binds and serves health on the base-supplied root path`() {
        val config = ConfigProvider.getConfig()

        // Both come from openbank-libs-runtime's application.yaml — this service declares neither.
        val rootPath = config.getValue("quarkus.management.root-path", String::class.java)
        assertThat(rootPath)
            .describedAs("root-path must come from the shared base; this service declares no management block")
            .isEqualTo("/q")

        // The management server runs on its own port, which Quarkus randomises for tests.
        val managementPort = config.getValue("quarkus.management.test-port", Int::class.javaObjectType)

        val body = RestAssured.given()
            .port(managementPort)
            .get("$rootPath/health/ready")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertThat(body)
            .describedAs("a bound management interface must actually answer, not merely be configured")
            .contains("UP")
    }
}
