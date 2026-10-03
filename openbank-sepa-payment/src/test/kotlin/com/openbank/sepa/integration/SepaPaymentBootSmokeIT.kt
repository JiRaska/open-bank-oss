// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Boot smoke test guarding the "released-but-never-booted" defect class.
 *
 * sepa-payment is a money-path service (version.txt) that previously had zero @QuarkusTest, so
 * boot/config defects could only surface in production. The same class bit psd2-service (#1163
 * missing runtime DB extensions, #1170 a duplicate YAML key dropping the HTTP port). This IT
 * boots the full app on a Testcontainers Postgres + Valkey, runs Flyway, and asserts the
 * readiness probe is UP and the service-info endpoint answers — the two signals that prove the
 * wiring, config and migrations survive a real boot. Mirrors clearing/interest/sdd's per-job
 * Testcontainers IT (issue #578).
 *
 * The lone `@Channel("events-out")` Kafka emitter is swapped to the in-memory connector so no
 * broker is needed and the readiness probe carries no Kafka health check.
 */
@QuarkusTest
@QuarkusTestResource(SepaPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
class SepaPaymentBootSmokeIT {

    @Inject
    lateinit var dataSource: DataSource

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory("events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `the service-info endpoint answers on the configured HTTP port`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-sepa-payment")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000008354", roles = ["ROLE_PAYMENTS"])
    fun `default boot keeps the new money path write disabled`() {
        assertThat(
            ConfigProvider.getConfig().getValue("openbank.sepa.workflow-observations.enabled", Boolean::class.java),
        ).isFalse()
        val response = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", "disabled-observations-${UUID.randomUUID()}")
            .body(
                """{
                  "type": "SCT",
                  "debtorAccountId": "${UUID.randomUUID()}",
                  "debtorIban": "CZ6508000000192000145399",
                  "debtorName": "Synthetic Debtor",
                  "creditorIban": "DE89370400440532013000",
                  "creditorName": "Synthetic Creditor",
                  "creditorBic": "COBADEFFXXX",
                  "amount": 10.00,
                  "currency": "EUR",
                  "endToEndId": null
                }
                """.trimIndent(),
            )
            .post("/api/v1/sepa-payments")
        assertThat(response.statusCode).isEqualTo(201)
        val paymentId = UUID.fromString(response.jsonPath().getString("id"))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM sepa_payment_workflow_observations WHERE payment_id = ?",
            ).use { statement ->
                statement.setObject(1, paymentId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getLong(1)).isZero()
                }
            }
            connection.prepareStatement(
                "SELECT count(*) FROM sepa_payment_outbox WHERE aggregate_id = ?",
            ).use { statement ->
                statement.setObject(1, paymentId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getLong(1)).isEqualTo(1)
                }
            }
        }
    }
}
