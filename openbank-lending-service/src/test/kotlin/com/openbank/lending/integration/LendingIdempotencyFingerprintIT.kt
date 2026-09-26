// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * #10916 — an `Idempotency-Key` is bound to the request it was first used with. Driven through the
 * real REST endpoint with the real Redis store: a same-body retry replays, a different body under
 * the same key is refused with 409 IDEMPOTENCY_KEY_REUSED and creates nothing, a retry whose JSON
 * differs only in key order, whitespace or the scale of an amount still replays (the fingerprint is
 * taken over the canonicalised DTO), and a refused request releases the key.
 */
@QuarkusTest
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class LendingIdempotencyFingerprintIT {

    @Inject
    lateinit var dataSource: DataSource

    private val firstDueDate = LocalDate.now().plusMonths(1)
    private val parties = mutableListOf<UUID>()

    /**
     * The Postgres container is shared with the other lending ITs, and `LendingSummaryIT` counts
     * the WHOLE book — so the applications this class creates are removed again.
     */
    @AfterEach
    fun removeCreatedApplications() {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM loan_application WHERE party_id = ?").use { statement ->
                parties.forEach {
                    statement.setObject(1, it)
                    statement.executeUpdate()
                }
            }
        }
        parties.clear()
    }

    @Test
    @TestSecurity(user = OFFICER, roles = ["ROLE_LENDING_OFFICER"])
    fun `same key and same body replays the first response without a second application`() {
        val key = UUID.randomUUID().toString()
        val partyId = UUID.randomUUID().also(parties::add)
        val first = apply(key, body(partyId, "10000.00"))
        assertThat(first.statusCode).isEqualTo(201)

        val replay = apply(key, body(partyId, "10000.00"))
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(replay.jsonPath().getString("id")).isEqualTo(first.jsonPath().getString("id"))
        assertThat(applicationsOf(partyId)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = OFFICER, roles = ["ROLE_LENDING_OFFICER"])
    fun `same key and a different body is refused with 409 and creates nothing`() {
        val key = UUID.randomUUID().toString()
        val partyId = UUID.randomUUID().also(parties::add)
        assertThat(apply(key, body(partyId, "10000.00")).statusCode).isEqualTo(201)

        val reused = apply(key, body(partyId, "90000.00"))
        assertThat(reused.statusCode).isEqualTo(409)
        assertThat(reused.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(reused.header("X-Idempotency-Replayed")).isNull()
        assertThat(applicationsOf(partyId)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = OFFICER, roles = ["ROLE_LENDING_OFFICER"])
    fun `same body with different key order and whitespace still replays`() {
        val key = UUID.randomUUID().toString()
        val partyId = UUID.randomUUID().also(parties::add)
        assertThat(apply(key, body(partyId, "10000.00")).statusCode).isEqualTo(201)

        val reordered = """
            {  "firstDueDate":"$firstDueDate", "termPeriods":12,
               "requestedAmount":{"currency":{"code":"EUR"},   "amount":"10000.00"},
               "nominalAnnualRate":0.05, "partyId":"$partyId"}
        """.trimIndent()
        val replay = apply(key, reordered)
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(applicationsOf(partyId)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = OFFICER, roles = ["ROLE_LENDING_OFFICER"])
    fun `an amount differing only in scale is the same request and replays`() {
        val key = UUID.randomUUID().toString()
        val partyId = UUID.randomUUID().also(parties::add)
        assertThat(apply(key, body(partyId, "1234.50")).statusCode).isEqualTo(201)

        // 1234.5 as a JSON number vs "1234.50" as a string: the same BigDecimal amount.
        val replay = apply(key, body(partyId, "1234.50").replace("\"1234.50\"", "1234.5"))
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(applicationsOf(partyId)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = OFFICER, roles = ["ROLE_LENDING_OFFICER"])
    fun `a refused application releases the key so the retry is not stuck in progress`() {
        val key = UUID.randomUUID().toString()
        val partyId = UUID.randomUUID().also(parties::add)
        // termPeriods 0 is refused by the use case (400) AFTER the key was reserved.
        val refused = body(partyId, "10000.00").replace("\"termPeriods\":12", "\"termPeriods\":0")
        repeat(2) {
            // Not 409 IDEMPOTENCY_REQUEST_IN_PROGRESS on the retry: the refusal released the marker.
            assertThat(apply(key, refused).statusCode).isEqualTo(400)
        }
        // The corrected application may reuse the key — no stale marker binds it to the refused body.
        assertThat(apply(key, body(partyId, "10000.00")).statusCode).isEqualTo(201)
        assertThat(applicationsOf(partyId)).isEqualTo(1)
    }

    private fun apply(key: String, json: String): Response = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", key)
        .body(json)
        .post("/api/v1/lending/applications")

    private fun body(partyId: UUID, amount: String) = """
        {"partyId":"$partyId","requestedAmount":{"amount":"$amount","currency":{"code":"EUR"}},
        "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"$firstDueDate"}
    """.trimIndent()

    private fun applicationsOf(partyId: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM loan_application WHERE party_id = ?").use {
            it.setObject(1, partyId)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private companion object {
        const val OFFICER = "fingerprint-it-proposer"
    }
}
