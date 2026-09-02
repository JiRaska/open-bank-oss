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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-0249 D3 — the concurrency guarantee, against a real Postgres.
 *
 * Two reserves of 3 000 against a 5 000 daily ceiling. Either one alone passes; together they do
 * not. This is the exact race the reserve-then-confirm design exists for, and the only test in the
 * suite that can observe it: the in-JVM fake in `SpendReservationServiceTest` cannot, and neither
 * could an in-JVM lock in production, where this service runs several replicas and the two racing
 * reserves are usually not even in the same process. The guarantee under test is the
 * `SELECT ... FOR UPDATE` on the grant row that `SpendReservationRepositoryImpl` takes first.
 *
 * The grant is seeded with plain JDBC rather than through the reactive repository, for the same
 * reason `DelegationExpirationSweepIT` does it: a reactive Panache repository cannot be driven
 * from a bare `@QuarkusTest` thread.
 */
@QuarkusTest
@QuarkusTestResource(SpendReservationConcurrencyIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class SpendReservationConcurrencyIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "spend-reservation-state-out",
        )

        override fun stop() = InMemoryConnector.clear()
    }

    private val grantee: UUID = UUID.randomUUID()

    private fun jdbc(): Connection {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        return DriverManager.getConnection(url, "openbank", "openbank_secret")
    }

    /** An ACTIVE account grant that may initiate payments, capped at 5 000 CZK per day. */
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
                ps.setObject(2, UUID.randomUUID())
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

    private fun reservedRows(grantId: UUID): Int = jdbc().use { c ->
        c.prepareStatement(
            "select count(*) from delegation_spend_reservations where grant_id = ? and state = 'RESERVED'",
        ).use { ps ->
            ps.setObject(1, grantId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun postReservation(grantId: UUID, amount: String, key: String): Int = RestAssured.given()
        .contentType(ContentType.JSON)
        .header(CUSTOMER_PARTY_HEADER, grantee.toString())
        .body("""{"amount": $amount, "currency": "CZK", "idempotencyKey": "$key"}""")
        .post("/api/v1/delegations/$grantId/reservations")
        .statusCode

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `two simultaneous reserves that would jointly breach the daily ceiling cannot both succeed`() {
        val grantId = seedGrant()
        val barrier = CyclicBarrier(CONCURRENT_CALLERS)
        val pool = Executors.newFixedThreadPool(CONCURRENT_CALLERS)

        val statuses = try {
            (1..CONCURRENT_CALLERS).map { n ->
                pool.submit<Int> {
                    barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    postReservation(grantId, "3000.00", "race-$n")
                }
            }.map { it.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(statuses).containsExactlyInAnyOrder(HTTP_CREATED, HTTP_CONFLICT)
        assertThat(reservedRows(grantId)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `the same idempotency key never takes the headroom twice`() {
        val grantId = seedGrant()

        val first = postReservation(grantId, "3000.00", "same-key")
        val replay = postReservation(grantId, "3000.00", "same-key")

        assertThat(first).isEqualTo(HTTP_CREATED)
        assertThat(replay).isEqualTo(HTTP_CREATED)
        assertThat(reservedRows(grantId)).isEqualTo(1)
        // 3 000 counted once, so the remaining 2 000 of the ceiling is still reservable.
        assertThat(postReservation(grantId, "2000.00", "second-key")).isEqualTo(HTTP_CREATED)
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `domestic reservation fails closed while its durable state stream is disabled`() {
        val grantId = seedGrant()

        val status = RestAssured.given()
            .contentType(ContentType.JSON)
            .header(CUSTOMER_PARTY_HEADER, grantee.toString())
            .body(
                """
                {
                  "amount": 100.00,
                  "currency": "CZK",
                  "idempotencyKey": "domestic-disabled",
                  "operationType": "DOMESTIC_PAYMENT"
                }
                """.trimIndent(),
            )
            .post("/api/v1/delegations/$grantId/reservations")
            .then().extract().statusCode()

        assertThat(status).isEqualTo(HTTP_SERVICE_UNAVAILABLE)
        assertThat(reservedRows(grantId)).isZero()
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `releasing a reservation gives the headroom back`() {
        val grantId = seedGrant()
        val reservationId = RestAssured.given()
            .contentType(ContentType.JSON)
            .header(CUSTOMER_PARTY_HEADER, grantee.toString())
            .body("""{"amount": 4000.00, "currency": "CZK", "idempotencyKey": "to-release"}""")
            .post("/api/v1/delegations/$grantId/reservations")
            .then().statusCode(HTTP_CREATED)
            .extract().path<String>("reservationId")

        assertThat(postReservation(grantId, "2000.00", "blocked")).isEqualTo(HTTP_CONFLICT)

        RestAssured.given()
            .contentType(ContentType.JSON)
            .header(CUSTOMER_PARTY_HEADER, grantee.toString())
            .post("/api/v1/delegations/$grantId/reservations/$reservationId/release")
            .then().statusCode(HTTP_OK)

        assertThat(postReservation(grantId, "2000.00", "after-release")).isEqualTo(HTTP_CREATED)
    }

    private companion object {
        const val CUSTOMER_PARTY_HEADER = "X-Customer-Party-Id"
        const val CONCURRENT_CALLERS = 2
        const val BARRIER_TIMEOUT_SECONDS = 10L
        const val CALL_TIMEOUT_SECONDS = 60L
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_CONFLICT = 409
        const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}
