// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * Boot smoke test guarding the "released-but-never-booted" defect class.
 *
 * domestic-payment is a money-path service (version.txt) that previously had zero @QuarkusTest,
 * so boot/config defects could only surface in production. The same class bit psd2-service
 * (#1163 missing runtime DB extensions, #1170 a duplicate YAML key dropping the HTTP port). This
 * IT boots the full app on a Testcontainers Postgres + Valkey, runs Flyway, and asserts the
 * readiness probe is UP and the service-info endpoint answers — the two signals that prove the
 * wiring, config and migrations survive a real boot. Mirrors clearing/interest/sdd's per-job
 * Testcontainers IT (issue #578).
 *
 * Both Kafka emitters (`@Channel("events-out")` and `@Channel("notification-requests-out")`) are
 * swapped to the in-memory connector so no broker is needed and the readiness probe carries no
 * Kafka health check.
 */
@QuarkusTest
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.domestic.it.PostgresRedisTestResource::class)
class DomesticPaymentBootSmokeIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("events-out", "notification-requests-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `the service-info endpoint answers on the configured HTTP port`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-domestic-payment")
    }

    @Test
    fun `proposal drafts migration includes a durable table and pooled id sequence`() {
        val config = ConfigProvider.getConfig()
        val url = config.getValue("quarkus.datasource.jdbc.url", String::class.java)
        val user = config.getValue("quarkus.datasource.username", String::class.java)
        val password = config.getValue("quarkus.datasource.password", String::class.java)
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_name = 'domestic_payment_proposal_drafts'",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getInt(1)).isEqualTo(1)
                }
                statement.executeQuery(
                    "SELECT increment_by FROM pg_sequences " +
                        "WHERE sequencename = 'domestic_payment_proposal_drafts_seq'",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getLong(1)).isEqualTo(50)
                }
            }
        }
    }
}
