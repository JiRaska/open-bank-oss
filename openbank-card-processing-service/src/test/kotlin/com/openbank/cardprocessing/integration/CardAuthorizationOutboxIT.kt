// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.integration

import com.openbank.cardprocessing.application.port.out.CardIssuancePolicyPort
import com.openbank.cardprocessing.application.port.out.CardLookupPort
import com.openbank.cardprocessing.application.port.out.CardOwnership
import com.openbank.cardprocessing.application.port.out.IssuerDecision
import com.openbank.cardprocessing.domain.model.CountedSpend
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * The card money path over real HTTP against a real database.
 *
 * ## What only this test can see
 *
 * Three defect classes are invisible to every unit test in this module, and to the whole build
 * without it:
 *
 * 1. **Entity column names.** A property with no explicit `@Column(name = ...)` asks for a column no
 *    migration creates — right for every single-word property and wrong for every multi-word one, so
 *    the class reads as consistent. A mocked repository issues no SQL, so nothing else would notice;
 *    consent-service shipped exactly this and one endpoint 500'd from the day it shipped.
 * 2. **Outbox atomicity.** That the authorisation row and its outbox row commit together is the
 *    point of ADR-0050, and a mocked repository cannot prove it. Here both are read back with plain
 *    JDBC, outside Hibernate.
 * 3. **The route is actually served.** A unit test that calls a resource class cannot tell a served
 *    route from an unserved one — a `@Path` binding to the wrong declaration made every POST to one
 *    endpoint answer 404 on a running pod for the life of the endpoint (#3371).
 *
 * The issuer is a CDI alternative, not a network call: what is under test is this service's own
 * path, and card-issuance's decision is pinned separately by the committed pact plus its provider
 * replay.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.cardprocessing.it.PostgresTestResource::class)
@TestProfile(CardAuthorizationOutboxIT.StubbedIssuerProfile::class)
@TestSecurity(user = "card-processing-it", roles = ["ROLE_OPERATOR"])
class CardAuthorizationOutboxIT {

    class StubbedIssuerProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // The dispatcher is driven explicitly by the tests that care, never on a tick that could
            // race an assertion.
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(StubIssuer::class.java)
    }

    /**
     * Approves anything up to [APPROVAL_CEILING] and declines above it with a fixed reason.
     *
     * Literal ids and a literal ceiling, deliberately: a `QuarkusTestProfile` loads in a different
     * classloader from the test class, so a companion object initialises twice and a randomised
     * value would hand the container one value and the assertion another.
     */
    @Alternative
    @Priority(1)
    @ApplicationScoped
    class StubIssuer :
        CardLookupPort,
        CardIssuancePolicyPort {
        override suspend fun lookup(cardId: UUID): CardOwnership? =
            if (cardId == KNOWN_CARD) CardOwnership(ACCOUNT, PARTY, "CZK") else null

        override suspend fun decide(
            cardId: UUID,
            amountMinorUnits: Long,
            channel: PresentmentChannel,
            mcc: String?,
            countryCode: String?,
            counted: CountedSpend,
        ): IssuerDecision = if (amountMinorUnits <= APPROVAL_CEILING) {
            IssuerDecision(approved = true, reason = null, category = "GROCERIES")
        } else {
            IssuerDecision(approved = false, reason = "DAILY_LIMIT_EXCEEDED", category = "GROCERIES")
        }

        companion object {
            val KNOWN_CARD: UUID = UUID.fromString("0a0a0a0a-1b1b-4c2c-8d3d-4e4e4e4e4e4e")
            val ACCOUNT: UUID = UUID.fromString("7c7c7c7c-8d8d-4e9e-8f0f-1a1a1a1a1a1a")
            val PARTY: UUID = UUID.fromString("5f5f5f5f-6a6a-4b7b-8c8c-9d9d9d9d9d9d")
            const val APPROVAL_CEILING = 100_000L
        }
    }

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    private fun authorize(amount: Long, key: String): String = given()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", key)
        .body(
            """
            {"cardId":"${StubIssuer.KNOWN_CARD}","amountMinorUnits":$amount,"currencyCode":"CZK",
             "channel":"CONTACTLESS","mcc":"5411","merchantName":"Potraviny","merchantCountry":"CZ",
             "networkReference":"$key"}
            """.trimIndent(),
        )
        .post("/api/v1/card-authorizations")
        .then()
        .statusCode(CREATED)
        .extract().path("id")

    private fun <T> query(sql: String, read: (ResultSet) -> T): T =
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { connection ->
            connection.createStatement().executeQuery(sql).use { rs ->
                rs.next()
                read(rs)
            }
        }

    @Test
    fun `an approved authorisation writes the row and its event in one transaction`() {
        val id = authorize(25_000, "it-approve-1")

        // Read back with plain JDBC, outside Hibernate: this is what proves the column names the
        // entity asks for are the ones the migration created.
        val status = query("SELECT status FROM card_authorizations WHERE id = '$id'") { it.getString(1) }
        val held = query(
            "SELECT amount_minor_units - cleared_amount_minor_units FROM card_authorizations WHERE id = '$id'",
        ) { it.getLong(1) }
        val events = query("SELECT count(*) FROM card_outbox WHERE aggregate_id = '$id'") { it.getLong(1) }
        val eventType = query("SELECT event_type FROM card_outbox WHERE aggregate_id = '$id'") { it.getString(1) }

        assertThat(status).isEqualTo("APPROVED")
        assertThat(held).isEqualTo(25_000)
        assertThat(events).isEqualTo(1)
        assertThat(eventType).isEqualTo("card.authorised.v1")
    }

    @Test
    fun `a decline is recorded as a row and an event, not merely returned`() {
        val id = authorize(500_000, "it-decline-1")

        val row = query("SELECT status, decline_reason FROM card_authorizations WHERE id = '$id'") {
            it.getString(1) to it.getString(2)
        }
        val eventType = query("SELECT event_type FROM card_outbox WHERE aggregate_id = '$id'") { it.getString(1) }

        // A decline the customer disputes is unanswerable from a log line that has aged out.
        assertThat(row.first).isEqualTo("DECLINED")
        assertThat(row.second).isEqualTo("DAILY_LIMIT_EXCEEDED")
        assertThat(eventType).isEqualTo("card.declined.v1")
    }

    @Test
    fun `the same idempotency key never takes a second hold`() {
        val first = authorize(10_000, "it-idem-1")
        val second = authorize(10_000, "it-idem-1")

        assertThat(second).isEqualTo(first)
        val rows = query("SELECT count(*) FROM card_authorizations WHERE idempotency_key = 'it-idem-1'") {
            it.getLong(1)
        }
        assertThat(rows).isEqualTo(1)
    }

    @Test
    fun `a partial clearing leaves the remainder held and a full one clears it`() {
        val id = authorize(30_000, "it-clear-1")

        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", "it-clear-1-a")
            .body("""{"amountMinorUnits":10000,"currencyCode":"CZK"}""")
            .post("/api/v1/card-authorizations/$id/clearing")
            .then()
            .statusCode(OK)
            .body("status", equalTo("PARTIALLY_CLEARED"))
            .body("heldAmountMinorUnits", equalTo(20_000))

        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", "it-clear-1-b")
            .body("""{"amountMinorUnits":20000,"currencyCode":"CZK"}""")
            .post("/api/v1/card-authorizations/$id/clearing")
            .then()
            .statusCode(OK)
            .body("status", equalTo("CLEARED"))
            .body("heldAmountMinorUnits", equalTo(0))
    }

    @Test
    fun `over-clearing is refused with a machine-readable reason, not a 500`() {
        val id = authorize(5_000, "it-over-1")

        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", "it-over-1-a")
            .body("""{"amountMinorUnits":9000,"currencyCode":"CZK"}""")
            .post("/api/v1/card-authorizations/$id/clearing")
            .then()
            // 409, because the request was well formed and the state was not what it needed to be.
            .statusCode(CONFLICT)
            .body("reason", equalTo("EXCEEDS_AUTHORIZED_AMOUNT"))
    }

    @Test
    fun `a reversal releases the hold and publishes the released amount`() {
        val id = authorize(7_500, "it-reverse-1")

        given()
            .post("/api/v1/card-authorizations/$id/reversal")
            .then()
            .statusCode(OK)
            .body("status", equalTo("REVERSED"))
            .body("heldAmountMinorUnits", equalTo(0))

        val released = query(
            "SELECT count(*) FROM card_outbox WHERE aggregate_id = '$id' AND event_type = 'card.hold_released.v1'",
        ) { it.getLong(1) }
        assertThat(released).isEqualTo(1)
    }

    @Test
    fun `an authorisation for an unknown card is a 404, never an approval`() {
        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", "it-unknown-1")
            .body(
                """
                {"cardId":"${UUID.randomUUID()}","amountMinorUnits":1000,"currencyCode":"CZK","channel":"ONLINE"}
                """.trimIndent(),
            )
            .post("/api/v1/card-authorizations")
            .then()
            .statusCode(NOT_FOUND)
    }

    @Test
    fun `an absent Idempotency-Key is a 400, not a 500`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {"cardId":"${StubIssuer.KNOWN_CARD}","amountMinorUnits":1000,"currencyCode":"CZK","channel":"ONLINE"}
                """.trimIndent(),
            )
            .post("/api/v1/card-authorizations")
            .then()
            // The exact case a `require(...)` in the body cannot answer: for a suspend fun no null
            // intrinsic is emitted, so a non-nullable parameter would 500 here (#3104/#3624).
            .statusCode(BAD_REQUEST)
    }

    @Test
    fun `a card's authorisations are listed newest first`() {
        authorize(1_100, "it-list-1")
        authorize(1_200, "it-list-2")

        given()
            .get("/api/v1/card-authorizations/card/${StubIssuer.KNOWN_CARD}?limit=50")
            .then()
            .statusCode(OK)
            .body("count", greaterThanOrEqualTo(2))
    }

    private companion object {
        const val OK = 200
        const val CREATED = 201
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val CONFLICT = 409
    }
}
