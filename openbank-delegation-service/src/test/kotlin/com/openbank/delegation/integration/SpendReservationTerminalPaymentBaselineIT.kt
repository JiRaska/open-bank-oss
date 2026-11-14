// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.integration

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
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * Issue #16000 — what releases the spend reservation of a delegated domestic payment that ends
 * REJECTED or CANCELLED. Measured against a real Postgres, not argued from the code.
 *
 * The reservation lifecycle has exactly three states (`RESERVED`, `CONFIRMED`, `RELEASED`) and no
 * expiry column, TTL or sweep; the only thing that ever stops a reservation counting is its
 * `created_at` falling outside the calendar day/month window the ceiling sums over. These tests pin
 * that down for both shapes a delegated domestic payment can take:
 *
 *  - the customer-edge path live today, which confirms the reservation as soon as the rail ACCEPTS
 *    the instruction — a later sanctions/fraud reject or a cancel happens after that, and a
 *    confirmed reservation can no longer be released;
 *  - a reservation nobody settles at all, which keeps counting for the rest of its window.
 */
@QuarkusTest
@QuarkusTestResource(SpendReservationTerminalPaymentBaselineIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class SpendReservationTerminalPaymentBaselineIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "approval-group-revisions-out",
        ) + mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun stop() = InMemoryConnector.clear()
    }

    private val grantor: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()

    private fun jdbc(): Connection {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        return DriverManager.getConnection(url, "openbank", "openbank_secret")
    }

    /** A grant with a MONTHLY ceiling only, so a backdated row inside the month still counts. */
    private fun seedGrant(): UUID {
        val id = UUID.randomUUID()
        jdbc().use { c ->
            c.prepareStatement(
                """
                insert into delegation_grants
                    (id, grantor_party_id, grantee_party_id, resource_type, resource_id, approval_policy,
                     monthly_limit_amount, monthly_limit_currency, valid_from, valid_to, status, created_at, updated_at)
                values (?, ?, ?, 'ACCOUNT', ?, 'SOLO', 5000.00, 'CZK',
                        now() - interval '60 days', now() + interval '30 days', 'ACTIVE', now(), now())
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

    private fun reserve(grantId: UUID, amount: String, key: String): Int = RestAssured.given()
        .contentType(ContentType.JSON)
        .header(CUSTOMER_PARTY_HEADER, grantee.toString())
        .body("""{"amount": $amount, "currency": "CZK", "idempotencyKey": "$key"}""")
        .post("/api/v1/delegations/$grantId/reservations")
        .then().extract().statusCode()

    private fun reservationId(grantId: UUID, key: String): String = jdbc().use { c ->
        c.prepareStatement(
            "select id from delegation_spend_reservations where grant_id = ? and idempotency_key = ?",
        ).use { ps ->
            ps.setObject(1, grantId)
            ps.setString(2, key)
            ps.executeQuery().use { rs ->
                check(rs.next())
                rs.getString(1)
            }
        }
    }

    private fun settle(grantId: UUID, reservationId: String, verb: String): Int = RestAssured.given()
        .contentType(ContentType.JSON)
        .header(CUSTOMER_PARTY_HEADER, grantee.toString())
        .post("/api/v1/delegations/$grantId/reservations/$reservationId/$verb")
        .then().extract().statusCode()

    private fun state(reservationId: String): String = jdbc().use { c ->
        c.prepareStatement("select state from delegation_spend_reservations where id = ?::uuid").use { ps ->
            ps.setString(1, reservationId)
            ps.executeQuery().use { rs ->
                check(rs.next())
                rs.getString(1)
            }
        }
    }

    private fun backdate(reservationId: String, sql: String) = jdbc().use { c ->
        c.prepareStatement("update delegation_spend_reservations set created_at = $sql where id = ?::uuid").use { ps ->
            ps.setString(1, reservationId)
            check(ps.executeUpdate() == 1)
        }
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `edge path - a payment rejected or cancelled after acceptance keeps its headroom consumed`() {
        val grantId = seedGrant()
        assertThat(reserve(grantId, "4000.00", "pay-1")).isEqualTo(HTTP_CREATED)
        val rid = reservationId(grantId, "pay-1")
        // customer-edge confirms on the rail's 2xx (CustomerEdgeResource.settleDelegatedSpend).
        assertThat(settle(grantId, rid, "confirm")).isEqualTo(HTTP_OK)

        // The payment is later REJECTED (sanctions/fraud) or CANCELLED. Nothing in this service
        // hears about it, and asking to give the headroom back is refused outright.
        assertThat(settle(grantId, rid, "release")).isEqualTo(HTTP_CONFLICT)
        assertThat(state(rid)).isEqualTo("CONFIRMED")
        assertThat(reserve(grantId, "2000.00", "pay-2")).isEqualTo(HTTP_CONFLICT)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `an unsettled reservation does not expire - it counts until its calendar window ends`() {
        val grantId = seedGrant()
        assertThat(reserve(grantId, "4000.00", "held")).isEqualTo(HTTP_CREATED)
        val rid = reservationId(grantId, "held")
        // Older than the admission chain's 48 h hold could ever be, but still inside this month.
        backdate(rid, THREE_DAYS_AGO_THIS_MONTH)

        assertThat(state(rid)).isEqualTo("RESERVED")
        assertThat(reserve(grantId, "2000.00", "next")).isEqualTo(HTTP_CONFLICT)

        // The only thing that frees it: the window rolls over. Still RESERVED, just no longer summed.
        backdate(rid, LAST_MONTH)
        assertThat(state(rid)).isEqualTo("RESERVED")
        assertThat(reserve(grantId, "2000.00", "next-month")).isEqualTo(HTTP_CREATED)
    }

    private companion object {
        const val CUSTOMER_PARTY_HEADER = "X-Customer-Party-Id"
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_CONFLICT = 409

        /** Month start in the ceiling's own zone (SpendWindows.ZONE). */
        const val MONTH_START = "(date_trunc('month', now() at time zone 'Europe/Prague') at time zone 'Europe/Prague')"
        const val THREE_DAYS_AGO_THIS_MONTH = "greatest(now() - interval '3 days', $MONTH_START)"
        const val LAST_MONTH = "$MONTH_START - interval '1 day'"
    }
}
