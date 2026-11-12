// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.sanctions.application.usecase.SanctionsMonitoringSettings
import com.openbank.sanctions.it.MonitoringFixture.Companion.OWNER
import com.openbank.sanctions.it.MonitoringFixture.Companion.SUBJECT_PREFIX
import com.openbank.sanctions.it.MonitoringFixture.Companion.UNLISTED
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.sql.DataSource

/**
 * The configuration key itself switches monitoring on (ADR-0376).
 *
 * `SanctionsOngoingMonitoringIT` replaces the settings bean, so it would stay green if the
 * property name in `SanctionsMonitoringSettings` and the one in `application.yaml` drifted
 * apart, and the feature could then never be enabled. Here the real key is set and nothing is
 * replaced: the bean reads it, and a registration is accepted over HTTP.
 */
@QuarkusTest
@QuarkusTestResource(value = SanctionsMonitoringFlagIT.MonitoringEnabled::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
class SanctionsMonitoringFlagIT {

    class MonitoringEnabled : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = mapOf("openbank.sanctions.monitoring.enabled" to "true")

        override fun stop() = Unit
    }

    @Inject
    lateinit var settings: SanctionsMonitoringSettings

    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `the configuration key enables registration`() {
        val subject = "${SUBJECT_PREFIX}flag"
        try {
            assertThat(settings.enabled).isTrue()

            val response = RestAssured.given().contentType("application/json")
                .body("""{"entityType":"INDIVIDUAL","name":"$UNLISTED"}""")
                .put("/api/v3/sanctions/monitored-subjects/$OWNER/$subject")

            assertThat(response.statusCode).isEqualTo(201)
        } finally {
            dataSource.connection.use { c ->
                c.createStatement().use { it.execute("DELETE FROM sanctions_monitored_subjects") }
            }
        }
    }
}
