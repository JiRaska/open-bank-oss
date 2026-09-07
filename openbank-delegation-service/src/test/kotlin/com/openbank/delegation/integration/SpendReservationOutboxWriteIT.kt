// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * ADR-0249 D4 (issue #5728) — the spend-reservation audit trail, proved by the only means that can.
 *
 * **Why an IT and not a unit test.** The guarantee is that the reservation row and its outbox row
 * commit TOGETHER. A mocked repository cannot show that: it can only show that the use case handed
 * an event to something. So this drives the real REST endpoint (the only way to get a Vert.x
 * context for the reactive repository) and reads both tables with plain JDBC — the fleet pattern
 * from `LendingOutboxWriteIT` and `ConsentRevocationOutboxIT`.
 *
 * **What it asserts is CONTENT, not presence.** An outbox row exists is a weak claim; the row must
 * name the right event type, the right grant and reservation, both parties, the amount, a real
 * `occurredAt`, and `sourceService` — which `AuditConsumer` falls back to `"unknown"` for, with no
 * error, when a producer omits it (#3994/#5256).
 */
@QuarkusTest
@QuarkusTestResource(SpendReservationOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class SpendReservationOutboxWriteIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("delegation-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    private val mapper = ObjectMapper()
    private val grantor: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()

    private fun jdbc(): Connection {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        return DriverManager.getConnection(url, "openbank", "openbank_secret")
    }

    private fun seedGrant(): UUID {
        val id = UUID.randomUUID()
        jdbc().use { c ->
            c.prepareStatement(
                """
                insert into delegation_grants
                    (id, grantor_party_id, grantee_party_id, resource_type, resource_id, approval_policy,
                     daily_limit_amount, daily_limit_currency, valid_from, valid_to, status, created_at, updated_at)
                values (?, ?, ?, 'ACCOUNT', ?, 'SOLO', 5000.00, 'CZK',
                        now() - interval '1 day', now() + interval '30 days', 'ACTIVE', now(), now())
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, grantor)
                ps.setObject(3, grantee)
                ps.setObject(4, UUID.randomUUID())
                ps.executeUpdate()
            }
            c.prepareStatement(
                "insert into delegation_capabilities (grant_id, capability) values (?, 'ACCOUNT_INITIATE_PAYMENT')",
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Outbox payloads for one grant, newest last. Read with plain JDBC — no ORM in the assertion. */
    private fun outboxPayloads(grantId: UUID, eventType: String): List<String> = jdbc().use { c ->
        c.prepareStatement(
            "select payload from delegation_outbox where aggregate_id = ? and event_type = ? order by id",
        ).use { ps ->
            ps.setObject(1, grantId)
            ps.setString(2, eventType)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }

    private fun reserve(grantId: UUID, amount: String, key: String): String = RestAssured.given()
        .contentType(ContentType.JSON)
        .header(CUSTOMER_PARTY_HEADER, grantee.toString())
        .body("""{"amount": $amount, "currency": "CZK", "idempotencyKey": "$key"}""")
        .post("/api/v1/delegations/$grantId/reservations")
        .then().statusCode(HTTP_CREATED)
        .extract().path("reservationId")

    private fun settle(grantId: UUID, reservationId: String, verb: String) {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .header(CUSTOMER_PARTY_HEADER, grantee.toString())
            .post("/api/v1/delegations/$grantId/reservations/$reservationId/$verb")
            .then().statusCode(HTTP_OK)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `a reserve commits its audit event in the same transaction as the reservation row`() {
        val before = Instant.now()
        val grantId = seedGrant()

        val reservationId = reserve(grantId, "1250.00", "payment-7")

        val payloads = outboxPayloads(grantId, "SpendReserved")
        assertThat(payloads).hasSize(1)
        val node = mapper.readTree(payloads.single())
        assertThat(node.get("eventType").asText()).isEqualTo("SpendReserved")
        assertThat(node.get("aggregateType").asText()).isEqualTo("DelegationGrant")
        assertThat(node.get("aggregateId").asText()).isEqualTo(grantId.toString())
        assertThat(node.get("reservationId").asText()).isEqualTo(reservationId)
        assertThat(node.get("grantorPartyId").asText()).isEqualTo(grantor.toString())
        assertThat(node.get("granteePartyId").asText()).isEqualTo(grantee.toString())
        assertThat(node.get("idempotencyKey").asText()).isEqualTo("payment-7")
        assertThat(node.get("amount").get("amount").decimalValue()).isEqualByComparingTo(BigDecimal("1250.00"))
        assertThat(node.get("amount").get("currency").asText()).isEqualTo("CZK")
        // The attribution field AuditConsumer falls back to "unknown" for, silently (#3994/#5256).
        assertThat(node.get("sourceService").asText()).isEqualTo("delegation-service")
        // Recency, never isNotNull(): Instant.EPOCH passes isNotNull() and reads as 1970
        // (#3874/#3883). Only a bounded window can tell a real clock reading from a default.
        assertThat(Instant.parse(node.get("occurredAt").asText())).isBetween(before, Instant.now())
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `confirm and release each commit their own audit event`() {
        val before = Instant.now()
        val grantId = seedGrant()
        val toConfirm = reserve(grantId, "100.00", "to-confirm")
        val toRelease = reserve(grantId, "200.00", "to-release")

        settle(grantId, toConfirm, "confirm")
        settle(grantId, toRelease, "release")

        val confirmed = mapper.readTree(outboxPayloads(grantId, "SpendConfirmed").single())
        assertThat(confirmed.get("reservationId").asText()).isEqualTo(toConfirm)
        assertThat(confirmed.get("grantorPartyId").asText()).isEqualTo(grantor.toString())
        assertThat(confirmed.get("granteePartyId").asText()).isEqualTo(grantee.toString())
        assertThat(confirmed.get("amount").get("amount").decimalValue()).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(confirmed.get("sourceService").asText()).isEqualTo("delegation-service")
        assertThat(Instant.parse(confirmed.get("occurredAt").asText())).isBetween(before, Instant.now())
        assertThat(confirmed.hasNonNull("settledAt")).isTrue()

        val released = mapper.readTree(outboxPayloads(grantId, "SpendReleased").single())
        assertThat(released.get("reservationId").asText()).isEqualTo(toRelease)
        assertThat(released.get("amount").get("amount").decimalValue()).isEqualByComparingTo(BigDecimal("200.00"))
        assertThat(released.get("sourceService").asText()).isEqualTo("delegation-service")
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `a replayed reserve and a no-op re-confirm write no second outbox row`() {
        val grantId = seedGrant()
        val reservationId = reserve(grantId, "100.00", "same-key")
        reserve(grantId, "100.00", "same-key")

        settle(grantId, reservationId, "confirm")
        settle(grantId, reservationId, "confirm")

        // One reservation and one settlement happened, so one event of each may exist. A second
        // would claim a spend that never took headroom.
        assertThat(outboxPayloads(grantId, "SpendReserved")).hasSize(1)
        assertThat(outboxPayloads(grantId, "SpendConfirmed")).hasSize(1)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `a refused reserve leaves neither a reservation row nor an outbox row`() {
        val grantId = seedGrant()
        reserve(grantId, "4000.00", "first")

        RestAssured.given()
            .contentType(ContentType.JSON)
            .header(CUSTOMER_PARTY_HEADER, grantee.toString())
            .body("""{"amount": 2000.00, "currency": "CZK", "idempotencyKey": "refused"}""")
            .post("/api/v1/delegations/$grantId/reservations")
            .then().statusCode(HTTP_CONFLICT)

        // Atomicity in the other direction: nothing committed, so nothing was audited. If the
        // event were published outside the transaction, this row would exist.
        assertThat(outboxPayloads(grantId, "SpendReserved")).hasSize(1)
    }

    private companion object {
        const val CUSTOMER_PARTY_HEADER = "X-Customer-Party-Id"
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_CONFLICT = 409
    }
}
