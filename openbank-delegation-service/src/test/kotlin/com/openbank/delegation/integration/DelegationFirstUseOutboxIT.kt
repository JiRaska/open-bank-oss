// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import java.time.Instant
import java.util.UUID

/**
 * ADR-0249 D4 / #5728 — the grantor is told the FIRST time a delegate spends, exactly once.
 *
 * Real Postgres and a real HTTP request, deliberately. A mocked repository cannot see any of the
 * three properties under test: that the event and the reservation commit in one transaction, that
 * the count which decides "first" runs under the same row lock that serialises reserves, and that
 * a second reserve on the same grant therefore adds no second event. A reactive Panache repo also
 * cannot be driven from a bare `@QuarkusTest` thread at all (`No current Vertx context found`) —
 * only a real request carries the Vert.x context, which is why every assertion below reads the row
 * back over plain JDBC rather than through the repository under test.
 *
 * `EVENT_TYPE` is spelled as a literal rather than imported from `DelegationFirstUsed`: this test
 * was written against unmodified `main` first, where that class does not exist, and it must fail
 * there by finding zero rows rather than by failing to compile.
 */
@QuarkusTest
@QuarkusTestResource(DelegationFirstUseOutboxIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DelegationFirstUseOutboxIT.OutboxRetainedProfile::class)
class DelegationFirstUseOutboxIT {
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "spend-reservation-state-out",
        )

        override fun stop() = InMemoryConnector.clear()
    }

    /** Dispatch off so the rows stay readable; the state stream stays off to prove it is not the gate. */
    class OutboxRetainedProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "openbank.delegation.spend-reservation-state-events-enabled" to "false",
        )
    }

    private val mapper = ObjectMapper().findAndRegisterModules()
    private val grantee = UUID.fromString("0199a333-0000-7000-8000-000000000001")

    private fun jdbc(): Connection {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        return DriverManager.getConnection(url, "openbank", "openbank_secret")
    }

    private fun seedGrant(): Pair<UUID, UUID> {
        val id = UUID.randomUUID()
        val grantor = UUID.randomUUID()
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
                statement.setObject(2, grantor)
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
        return id to grantor
    }

    private fun reserve(grantId: UUID, key: String, amount: String = "125.50"): UUID = UUID.fromString(
        RestAssured.given()
            .contentType(ContentType.JSON)
            .header(CUSTOMER_PARTY_HEADER, grantee.toString())
            .body("""{"amount":$amount,"currency":"CZK","idempotencyKey":"$key"}""")
            .post("/api/v1/delegations/$grantId/reservations")
            .then().statusCode(HTTP_CREATED)
            .extract().path("reservationId"),
    )

    private fun firstUsePayloads(grantId: UUID): List<JsonNode> = jdbc().use { connection ->
        connection.prepareStatement(
            "SELECT payload FROM delegation_outbox WHERE aggregate_id = ? AND event_type = ? ORDER BY id",
        ).use { statement ->
            statement.setObject(1, grantId)
            statement.setString(2, EVENT_TYPE)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(mapper.readTree(result.getString(1)))
                }
            }
        }
    }

    private fun reservationCount(grantId: UUID): Int = jdbc().use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM delegation_spend_reservations WHERE grant_id = ?",
        ).use { statement ->
            statement.setObject(1, grantId)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `the first reserve announces first use to the grantor and later reserves do not`() {
        val (grantId, grantor) = seedGrant()
        val before = Instant.now()

        val reservationId = reserve(grantId, "first-use-1")

        val afterFirst = firstUsePayloads(grantId)
        assertThat(afterFirst).hasSize(1)
        val payload = afterFirst.single()
        assertThat(payload.get("aggregateId").asText()).isEqualTo(grantId.toString())
        assertThat(payload.get("grantorPartyId").asText()).isEqualTo(grantor.toString())
        assertThat(payload.get("granteePartyId").asText()).isEqualTo(grantee.toString())
        assertThat(payload.get("reservationId").asText()).isEqualTo(reservationId.toString())
        assertThat(payload.get("resourceType").asText()).isEqualTo("ACCOUNT")
        assertThat(payload.get("eventType").asText()).isEqualTo(EVENT_TYPE)
        assertThat(payload.path("lifecycleRevision").isMissingNode).isTrue()

        // Recency, never non-nullity: Instant.EPOCH is non-null and would pass `isNotNull`, and a
        // 1970 timestamp on an append-only audit row cannot be corrected afterwards.
        val occurredAt = Instant.parse(payload.get("occurredAt").asText())
        assertThat(occurredAt).isBetween(before.minusSeconds(SKEW_SECONDS), Instant.now().plusSeconds(SKEW_SECONDS))

        // A SECOND, distinct reservation on the same grant is a use, but not a FIRST use.
        reserve(grantId, "first-use-2", amount = "10.00")
        assertThat(reservationCount(grantId)).isEqualTo(2)
        assertThat(firstUsePayloads(grantId)).hasSize(1)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `an idempotent replay of the first reserve does not announce first use twice`() {
        val (grantId, _) = seedGrant()
        val first = reserve(grantId, "replayed-key")
        val replay = reserve(grantId, "replayed-key")

        assertThat(replay).isEqualTo(first)
        assertThat(reservationCount(grantId)).isEqualTo(1)
        assertThat(firstUsePayloads(grantId)).hasSize(1)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `a refused reserve creates neither a reservation nor a first-use announcement`() {
        val (grantId, _) = seedGrant()

        RestAssured.given()
            .contentType(ContentType.JSON)
            .header(CUSTOMER_PARTY_HEADER, grantee.toString())
            .body("""{"amount":9999.00,"currency":"CZK","idempotencyKey":"over-ceiling"}""")
            .post("/api/v1/delegations/$grantId/reservations")
            .then().statusCode(HTTP_CONFLICT)

        assertThat(reservationCount(grantId)).isZero()
        assertThat(firstUsePayloads(grantId)).isEmpty()
    }

    private companion object {
        const val EVENT_TYPE = "DelegationFirstUsed"
        const val CUSTOMER_PARTY_HEADER = "X-Customer-Party-Id"
        const val HTTP_CREATED = 201
        const val HTTP_CONFLICT = 409
        const val SKEW_SECONDS = 120L
    }
}
