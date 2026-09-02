// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.domain.event.DelegationSpendReservationStateChanged
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Real-Postgres proof that reservation state and outbox evidence commit together. */
@QuarkusTest
@QuarkusTestResource(SpendReservationStateOutboxIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(SpendReservationStateOutboxIT.StateStreamProfile::class)
class SpendReservationStateOutboxIT {
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "spend-reservation-state-out",
        )

        override fun stop() = InMemoryConnector.clear()
    }

    class StateStreamProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.delegation.spend-reservation-state-events-enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    private val mapper = ObjectMapper().findAndRegisterModules()
    private val grantee = UUID.fromString("0199a222-0000-7000-8000-000000000001")

    private fun jdbc(): Connection {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        return DriverManager.getConnection(url, "openbank", "openbank_secret")
    }

    private fun seedGrant(): UUID {
        val id = UUID.randomUUID()
        jdbc().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO delegation_grants
                    (id, grantor_party_id, grantee_party_id, resource_type, resource_id,
                     approval_policy, daily_limit_amount, daily_limit_currency, valid_from,
                     valid_to, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACCOUNT', ?, 'SOLO', 5000.00, 'CZK', NOW() - INTERVAL '1 day',
                        NOW() + INTERVAL '30 days', 'ACTIVE', NOW(), NOW())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, grantee)
                statement.setObject(4, UUID.randomUUID())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO delegation_capabilities (grant_id, capability) VALUES (?, 'ACCOUNT_INITIATE_PAYMENT')",
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun reserve(grantId: UUID, key: String, domestic: Boolean): UUID {
        val operation = if (domestic) ", \"operationType\": \"DOMESTIC_PAYMENT\"" else ""
        return UUID.fromString(
            RestAssured.given()
                .contentType(ContentType.JSON)
                .header(CUSTOMER_PARTY_HEADER, grantee.toString())
                .body("""{"amount":125.50,"currency":"CZK","idempotencyKey":"$key"$operation}""")
                .post("/api/v1/delegations/$grantId/reservations")
                .then().statusCode(HTTP_CREATED)
                .extract().path("reservationId"),
        )
    }

    private fun statePayloads(reservationId: UUID): List<JsonNode> = jdbc().use { connection ->
        connection.prepareStatement(
            """
            SELECT payload FROM delegation_outbox
             WHERE aggregate_id = ? AND event_type = ?
             ORDER BY (payload::jsonb ->> 'reservationVersion')::BIGINT
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, reservationId)
            statement.setString(2, DelegationSpendReservationStateChanged.EVENT_TYPE)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(mapper.readTree(result.getString(1)))
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `reserve and terminal transition write ordered complete snapshots atomically`() {
        val grantId = seedGrant()
        val reservationId = reserve(grantId, "atomic-state", domestic = true)

        assertThat(statePayloads(reservationId).map { it.get("reservationVersion").asLong() })
            .containsExactly(1L)

        RestAssured.given()
            .contentType(ContentType.JSON)
            .header(CUSTOMER_PARTY_HEADER, grantee.toString())
            .post("/api/v1/delegations/$grantId/reservations/$reservationId/confirm")
            .then().statusCode(HTTP_OK)

        val payloads = statePayloads(reservationId)
        assertThat(payloads.map { it.get("reservationVersion").asLong() }).containsExactly(1L, 2L)
        assertThat(payloads.map { it.get("state").asText() }).containsExactly("RESERVED", "CONFIRMED")
        payloads.forEach { payload ->
            assertThat(payload.get("reservationId").asText()).isEqualTo(reservationId.toString())
            assertThat(payload.get("delegationId").asText()).isEqualTo(grantId.toString())
            assertThat(payload.get("idempotencyKeyHash").asText()).matches("[0-9a-f]{64}")
            assertThat(payload.has("idempotencyKey")).isFalse()
        }
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `rail neutral reservation remains backward compatible and emits no domestic snapshot`() {
        val reservationId = reserve(seedGrant(), "neutral-state", domestic = false)
        assertThat(statePayloads(reservationId)).isEmpty()
    }

    private companion object {
        const val CUSTOMER_PARTY_HEADER = "X-Customer-Party-Id"
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
    }
}
