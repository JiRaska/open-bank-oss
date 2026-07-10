// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.integration

import com.openbank.analytics.it.RedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boot smoke test guarding the "released-but-never-booted" defect class (CLAUDE.md pitfall):
 * analytics-sink had never had a real `@QuarkusTest` boot before, so its several
 * `@ConfigProperty(defaultValue = "")` fields typed as plain `String` — which SmallRye Config's
 * `ConfigRecorder.validateConfigProperties` eagerly validates at boot, and whose built-in String
 * converter treats an empty-string-resolved value as "no value", throwing `SRCFG00040` — went
 * undiscovered. All are now `Optional<String>` (see [com.openbank.analytics.infrastructure.schema.
 * ConfigSchemaCatalogSource], `ClickHouseClient`, `ClickHouseAnalyticsSink`,
 * `HttpReconciliationSource`, `S3WormArchive`, `VaultCryptoErasure`). A real Redpanda broker is
 * needed because the `analytics-events-in` incoming Kafka channel initialises at boot.
 */
@QuarkusTest
@QuarkusTestResource(RedpandaTestResource::class)
class AnalyticsSinkBootSmokeIT {

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `the service-info endpoint answers on the configured HTTP port`() {
        val body = (
            Given { this } When { get("/api/v1/info") } Then { statusCode(200) }
            ).extract().body().asString()
        assertThat(body).contains("openbank-analytics-sink")
    }
}
