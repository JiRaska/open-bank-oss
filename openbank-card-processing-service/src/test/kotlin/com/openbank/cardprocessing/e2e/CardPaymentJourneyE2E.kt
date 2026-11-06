// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresTestResource
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.equalToIgnoringCase
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end journey for a card payment on this service's own surface: **authorise and hold, spend
 * against the limit the issuer enforces over the spend this service counts, clear in two
 * presentments, reach the books once per presentment, reverse, and fail closed.**
 *
 * Driven through the real HTTP surface against real Postgres, with both real outbound adapters
 * (the card-issuance REST client and the generated transaction-service client) speaking over a real
 * socket. No use case, repository, port or domain object is touched or replaced.
 *
 * ### Service boundaries, stated and not pretended
 *
 * card-issuance and transaction-service are socket-level stand-ins here. Their real behaviour is
 * held by the committed pacts and their provider replays. The stand-in issuer applies one rule, a
 * daily limit over the counted spend it is SENT, which is what makes this service's own count
 * observable. Authentication is `@TestSecurity`, so the JWT and OPA legs are not claimed here.
 * Fraud scoring is shadow-only and stays off.
 *
 * ### What this adds over the integration tests
 *
 * The sibling ITs replace the issuer and the ledger port with CDI alternatives. Here the observable
 * for "the books were reached" is the request that crossed the socket: its amount in major units,
 * its debited account, its rail and its idempotency key. Those are the only facts the ledger ever
 * learns about a card payment.
 */
@QuarkusTest
@QuarkusTestResource(
    PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_card_processing_it")],
)
@QuarkusTestResource(CardPaymentJourneyE2E.Downstreams::class, restrictToAnnotatedClass = true)
@TestProfile(CardPaymentJourneyE2E.Profile::class)
@TestSecurity(user = "card-journey-e2e", roles = ["ROLE_OPERATOR"])
class CardPaymentJourneyE2E {

    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            // %test switches posting off; this journey is the test that needs it on.
            "openbank.card-processing.ledger-posting-enabled" to "true",
            // Without this every outbound call dies fetching a token from an absent Keycloak.
            "quarkus.oidc-client.enabled" to "false",
        )
    }

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @ConfigProperty(name = "quarkus.rest-client.transaction-api.url")
    lateinit var downstreamUrl: String

    private val mapper = ObjectMapper()

    @Test
    fun `a payment is held, cleared in two presentments, booked once per presentment and then closed`() {
        val card = UUID.randomUUID()
        val merchant = "E2E-${UUID.randomUUID().toString().take(8)}"
        val authorization = authorize(card, 60_000, merchant).statusCode(CREATED)
            .body("status", equalTo("APPROVED"))
            .body("heldAmountMinorUnits", equalTo(60_000))
            .body("accountId", equalTo(accountOf(card).toString()))
            .extract().path<String>("id")
        assertThat(postingsFor(merchant)).describedAs("an authorisation holds; it does not post").isEmpty()

        // Read back over a different code path than the one that answered the POST.
        given().get("$AUTHORIZATIONS/$authorization").then().statusCode(OK)
            .body("status", equalTo("APPROVED"))
            // The network lookup normalises the descriptor's case; the descriptor itself must survive.
            .body("merchantName", equalToIgnoringCase(merchant))
            .body("cardId", equalTo(card.toString()))

        val firstKey = "clear-${UUID.randomUUID()}"
        clear(authorization, 20_000, firstKey).statusCode(OK)
            .body("status", equalTo("PARTIALLY_CLEARED"))
            .body("heldAmountMinorUnits", equalTo(40_000))

        val booked = postingsFor(merchant).single()
        assertThat(booked.path("amount").decimalValue()).isEqualByComparingTo(BigDecimal("200.00"))
        assertThat(booked.path("currencyCode").asText()).isEqualTo("CZK")
        assertThat(booked.path("sourceAccountId").asText()).isEqualTo(accountOf(card).toString())
        assertThat(booked.path("rail").asText()).isEqualTo("CARD")

        // The acquirer retries: the first result is replayed, and neither the hold nor the books move.
        clear(authorization, 20_000, firstKey).statusCode(OK).body("heldAmountMinorUnits", equalTo(40_000))
        assertThat(postingsFor(merchant)).hasSize(1)

        // Same key, another amount: the key is a fingerprint, not a nonce.
        clear(authorization, 10_000, firstKey).statusCode(CONFLICT)
        // More than is still held, and another currency: refused with a reason, nothing applied.
        clear(authorization, 40_001, "clear-${UUID.randomUUID()}").statusCode(CONFLICT)
            .body("reason", equalTo("EXCEEDS_AUTHORIZED_AMOUNT"))
        clear(authorization, 1_000, "clear-${UUID.randomUUID()}", "EUR").statusCode(CONFLICT)
            .body("reason", equalTo("CURRENCY_MISMATCH"))
        assertThat(postingsFor(merchant)).hasSize(1)

        clear(authorization, 40_000, "clear-${UUID.randomUUID()}").statusCode(OK)
            .body("status", equalTo("CLEARED"))
            .body("heldAmountMinorUnits", equalTo(0))
            .body("clearedAmountMinorUnits", equalTo(60_000))

        val postings = postingsFor(merchant)
        assertThat(postings.map { it.path("amount").decimalValue() }.reduce(BigDecimal::add))
            .describedAs("the books received exactly the authorised money")
            .isEqualByComparingTo(BigDecimal("600.00"))
        assertThat(postings.map { it.path("idempotencyKey").asText() }.toSet()).hasSize(2)

        // Terminal: nothing more can be presented or released.
        clear(authorization, 1, "clear-${UUID.randomUUID()}").statusCode(CONFLICT)
            .body("reason", equalTo("NOT_HOLDING_FUNDS"))
        given().post("$AUTHORIZATIONS/$authorization/reversal").then().statusCode(CONFLICT)
            .body("reason", equalTo("NOT_HOLDING_FUNDS"))

        // The durable record, read outside Hibernate.
        assertThat(column("SELECT posting_status FROM card_clearings WHERE authorization_id = '$authorization'"))
            .containsExactly("POSTED", "POSTED")
        assertThat(column("SELECT event_type FROM card_outbox WHERE aggregate_id = '$authorization' ORDER BY id"))
            .containsExactly("card.authorised.v1", "card.cleared.v1", "card.cleared.v1")
    }

    @Test
    fun `a hold spends the daily limit until it is reversed, and a decline holds nothing`() {
        val card = UUID.randomUUID()
        val held = authorize(card, 70_000, "E2E-first").statusCode(CREATED)
            .body("status", equalTo("APPROVED")).extract().path<String>("id")

        // The issuer only knows what this service counted and sent, so 70 000 held + 40 000 asked
        // crossing the 100 000 limit proves the hold reached the count before it cleared.
        val declined = authorize(card, 40_000, "E2E-second").statusCode(CREATED)
            .body("status", equalTo("DECLINED"))
            .body("declineReason", equalTo("DAILY_LIMIT_EXCEEDED"))
            .body("heldAmountMinorUnits", equalTo(0))
            .extract().path<String>("id")
        clear(declined, 1_000, "clear-${UUID.randomUUID()}").statusCode(CONFLICT)
            .body("reason", equalTo("NOT_HOLDING_FUNDS"))

        given().post("$AUTHORIZATIONS/$held/reversal").then().statusCode(OK)
            .body("status", equalTo("REVERSED"))
            .body("heldAmountMinorUnits", equalTo(0))

        // The release gave the room back: the same request that was declined now goes through.
        authorize(card, 40_000, "E2E-third").statusCode(CREATED).body("status", equalTo("APPROVED"))

        given().get("$AUTHORIZATIONS/card/$card").then().statusCode(OK).body("count", equalTo(3))
    }

    @Test
    fun `an unknown card, a broken issuer and a refused posting never read as a booked payment`() {
        val unknown = UUID.fromString("00000000-0000-4000-8000-000000000404")
        authorize(unknown, 1_000, "E2E-unknown").statusCode(NOT_FOUND)
        assertThat(column("SELECT id::text FROM card_authorizations WHERE card_id = '$unknown'")).isEmpty()

        // Fail closed: an issuer that cannot evaluate its controls must not let spend through, and
        // the reason must not impersonate a policy decision.
        authorize(UUID.fromString(BROKEN_ISSUER_CARD), 1_000, "E2E-broken").statusCode(CREATED)
            .body("status", equalTo("DECLINED"))
            .body("declineReason", equalTo("ISSUER_UNAVAILABLE"))

        // The clearing is a fact the acquirer asserted, so it stands; the gap must stay visible.
        val refused = authorize(UUID.randomUUID(), 5_000, REFUSED_BY_LEDGER).statusCode(CREATED)
            .extract().path<String>("id")
        clear(refused, 5_000, "clear-${UUID.randomUUID()}").statusCode(OK).body("status", equalTo("CLEARED"))
        assertThat(column("SELECT posting_status FROM card_clearings WHERE authorization_id = '$refused'"))
            .containsExactly("FAILED")
    }

    @Test
    fun `an absent Idempotency-Key is refused before anything is recorded`() {
        val card = UUID.randomUUID()
        given().contentType(ContentType.JSON).body(authorizationBody(card, 1_000, "E2E-nokey"))
            .post(AUTHORIZATIONS).then().statusCode(BAD_REQUEST)
        assertThat(column("SELECT id::text FROM card_authorizations WHERE card_id = '$card'")).isEmpty()
    }

    private fun authorize(card: UUID, amount: Long, merchant: String): ValidatableResponse = given()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", "auth-${UUID.randomUUID()}")
        .body(authorizationBody(card, amount, merchant))
        .post(AUTHORIZATIONS)
        .then()

    private fun authorizationBody(card: UUID, amount: Long, merchant: String) =
        """
        {"cardId":"$card","amountMinorUnits":$amount,"currencyCode":"CZK","channel":"CONTACTLESS",
         "mcc":"5411","merchantName":"$merchant","merchantCountry":"CZ"}
        """.trimIndent()

    private fun clear(id: String, amount: Long, key: String, currency: String = "CZK"): ValidatableResponse = given()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", key)
        .body("""{"amountMinorUnits":$amount,"currencyCode":"$currency"}""")
        .post("$AUTHORIZATIONS/$id/clearing")
        .then()

    /** What transaction-service was actually sent; read over HTTP because the classloaders differ. */
    private fun postingsFor(merchant: String): List<JsonNode> = mapper
        .readTree(URI("$downstreamUrl/__postings").toURL().readText())
        .filter { it.path("description").asText().equals(merchant, ignoreCase = true) }

    private fun column(sql: String): List<String> =
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { connection ->
            connection.createStatement().executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    /**
     * One real HTTP server standing in for card-issuance and transaction-service. It keeps no card
     * table: the owning account is derived from the card id, so the test can state the expected
     * value without sharing memory across classloaders.
     */
    class Downstreams : QuarkusTestResourceLifecycleManager {
        private var server: HttpServer? = null

        override fun start(): Map<String, String> {
            val mapper = ObjectMapper()
            val postings = CopyOnWriteArrayList<JsonNode>()
            val seenKeys = ConcurrentHashMap.newKeySet<String>()
            val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            http.createContext("/api/v1/cards") { ex -> card(ex, mapper) }
            http.createContext("/api/v1/transactions") { ex ->
                val posting = mapper.readTree(ex.requestBody.readBytes())
                when {
                    posting.path("description").asText().equals(REFUSED_BY_LEDGER, ignoreCase = true) ->
                        reply(ex, UNPROCESSABLE, """{"code":"INSUFFICIENT_FUNDS","message":"refused"}""")

                    else -> {
                        if (seenKeys.add(posting.path("idempotencyKey").asText())) postings.add(posting)
                        reply(ex, CREATED, """{"id":"${UUID.randomUUID()}","status":"COMPLETED"}""")
                    }
                }
            }
            http.createContext("/__postings") { ex -> reply(ex, OK, mapper.writeValueAsString(postings)) }
            http.start()
            server = http
            val url = "http://127.0.0.1:${http.address.port}"
            return mapOf(
                "quarkus.rest-client.card-issuance-api.url" to url,
                "quarkus.rest-client.transaction-api.url" to url,
            )
        }

        override fun stop() {
            server?.stop(0)
        }

        private fun card(ex: HttpExchange, mapper: ObjectMapper) {
            val segments = ex.requestURI.path.removePrefix("/api/v1/cards/").split("/")
            val id = UUID.fromString(segments.first())
            when {
                id.toString().endsWith("000000000404") -> reply(ex, NOT_FOUND, """{"message":"no such card"}""")

                segments.size == 1 -> reply(
                    ex,
                    OK,
                    """{"id":"$id","accountId":"${accountOf(id)}","partyId":"${accountOf(id)}","status":"ACTIVE"}""",
                )

                id.toString() == BROKEN_ISSUER_CARD -> reply(ex, UNAVAILABLE, """{"message":"unavailable"}""")

                else -> {
                    val request = mapper.readTree(ex.requestBody.readBytes())
                    val spent = request.path("spentTodayMinorUnits").asLong()
                    val approved = spent + request.path("amountMinorUnits").asLong() <= DAILY_LIMIT
                    val reason = if (approved) "null" else "\"DAILY_LIMIT_EXCEEDED\""
                    reply(ex, OK, """{"approved":$approved,"declineReason":$reason,"category":"GROCERIES"}""")
                }
            }
        }

        private fun reply(ex: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private companion object {
        const val AUTHORIZATIONS = "/api/v1/card-authorizations"
        const val OK = 200
        const val CREATED = 201
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val UNPROCESSABLE = 422
        const val UNAVAILABLE = 503
        const val DAILY_LIMIT = 100_000L
        const val BROKEN_ISSUER_CARD = "00000000-0000-4000-8000-000000000503"
        const val REFUSED_BY_LEDGER = "E2E-refused-by-ledger"

        fun accountOf(card: UUID): UUID = UUID.nameUUIDFromBytes("account-of-$card".toByteArray())
    }
}
