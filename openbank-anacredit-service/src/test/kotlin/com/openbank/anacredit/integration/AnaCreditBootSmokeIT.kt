// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.anacredit.integration

import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boot smoke test guarding the "released-but-never-booted" defect class.
 *
 * anacredit-service is a released component (version.txt) with no GitOps deployment. It uses an
 * in-memory repository (no Postgres, no Kafka), so no Testcontainers resources are needed — a bare
 * @QuarkusTest is sufficient to boot the full application. This mirrors the pattern introduced for
 * lending-service (boot/config defects only surface at boot: duplicate YAML keys, missing CDI beans,
 * OIDC mis-wiring). The two assertions prove the wiring, config and OIDC override survive a real boot.
 */
@QuarkusTest
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
