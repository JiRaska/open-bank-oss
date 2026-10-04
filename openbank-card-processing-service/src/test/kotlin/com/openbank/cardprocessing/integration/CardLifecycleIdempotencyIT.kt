// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.integration

import com.openbank.cardprocessing.application.port.out.CardIssuancePolicyPort
import com.openbank.cardprocessing.application.port.out.CardIssuerUnavailableException
import com.openbank.cardprocessing.application.port.out.CardLookupPort
import com.openbank.cardprocessing.application.port.out.CardOwnership
import com.openbank.cardprocessing.application.port.out.IssuerDecision
import com.openbank.cardprocessing.domain.model.CountedSpend
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.infrastructure.scheme.SimulatedDisputeAdapter
import com.openbank.cardprocessing.infrastructure.scheme.SimulatedTokenisationAdapter
import com.openbank.libs.domain.cards.scheme.DisputeEvidence
import com.openbank.libs.domain.cards.scheme.DisputePort
import com.openbank.libs.domain.cards.scheme.NetworkToken
import com.openbank.libs.domain.cards.scheme.NetworkTokenStatus
import com.openbank.libs.domain.cards.scheme.SchemeDispute
import com.openbank.libs.domain.cards.scheme.SchemeResult
import com.openbank.libs.domain.cards.scheme.TokenRequestor
import com.openbank.libs.domain.cards.scheme.TokenisationPort
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.delay
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The token and dispute paths over real HTTP against a real database, where the defects live.
 *
 * ## What only this test can see
 *
 * - **Concurrent same-key requests.** Two requests released together by a latch, with the network
 *   held open long enough that both are in flight at once. Before the reservation, both reached the
 *   network (two wallet credentials, two chargebacks) and the loser's insert answered 500. A mocked
 *   repository cannot reproduce a unique-index race; only Postgres can.
 * - **The reservation commits atomically with the result.** Read back with plain JDBC.
 * - **Card state over the wire**: a blocked card is a 409, an unreachable card-issuance a 503.
 *
 * The networks are CDI alternatives wrapping the shipped simulators, adding only a delay and a call
 * counter: what is under test is this service's own path.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.cardprocessing.it.PostgresTestResource::class)
@TestProfile(CardLifecycleIdempotencyIT.SlowNetworkProfile::class)
@TestSecurity(user = "card-lifecycle-it", roles = ["ROLE_OPERATOR"])
class CardLifecycleIdempotencyIT {

    class SlowNetworkProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(
            StubCards::class.java,
            SlowTokenisation::class.java,
            SlowDisputes::class.java,
        )
    }

    /** Literal ids — a profile loads in another classloader, so a random companion value would differ. */
    @Alternative
    @ApplicationScoped
    class StubCards :
        CardLookupPort,
        CardIssuancePolicyPort {
        override suspend fun lookup(cardId: UUID): CardOwnership? = when (cardId) {
            ACTIVE_CARD -> CardOwnership(ACCOUNT, PARTY, "CZK", "ACTIVE")
            BLOCKED_CARD -> CardOwnership(ACCOUNT, PARTY, "CZK", "BLOCKED")
            UNREACHABLE_CARD -> throw CardIssuerUnavailableException(RuntimeException("connect refused"))
            else -> null
        }

        override suspend fun decide(
            cardId: UUID,
            amountMinorUnits: Long,
            channel: PresentmentChannel,
            mcc: String?,
            countryCode: String?,
            counted: CountedSpend,
        ): IssuerDecision = IssuerDecision(approved = true, reason = null, category = "GROCERIES")
    }

    /** The shipped simulator, held open for [HOLD_MS] so a second request overlaps the first. */
    @Alternative
    @ApplicationScoped
    class SlowTokenisation : TokenisationPort {
        private val delegate = SimulatedTokenisationAdapter(Clock.systemUTC())
        val provisions = AtomicInteger()

        override suspend fun provision(cardReference: String, requestor: TokenRequestor): SchemeResult<NetworkToken> {
            provisions.incrementAndGet()
            delay(HOLD_MS)
            return delegate.provision(cardReference, requestor)
        }

        override suspend fun listTokens(cardReference: String) = delegate.listTokens(cardReference)

        val statusChanges = AtomicInteger()

        override suspend fun changeStatus(
            tokenReference: String,
            status: NetworkTokenStatus,
        ): SchemeResult<NetworkToken> {
            statusChanges.incrementAndGet()
            delay(HOLD_MS)
            return delegate.changeStatus(tokenReference, status)
        }
    }

    @Alternative
    @ApplicationScoped
    class SlowDisputes : DisputePort {
        private val delegate = SimulatedDisputeAdapter(Clock.systemUTC())
        val opens = AtomicInteger()
        val evidence = AtomicInteger()

        override suspend fun open(
            networkReference: String,
            reasonCode: String,
            amountMinorUnits: Long,
            currencyCode: String,
        ): SchemeResult<SchemeDispute> {
            opens.incrementAndGet()
            delay(HOLD_MS)
            return delegate.open(networkReference, reasonCode, amountMinorUnits, currencyCode)
        }

        override suspend fun submitEvidence(evidence: DisputeEvidence): SchemeResult<SchemeDispute> {
            this.evidence.incrementAndGet()
            delay(HOLD_MS)
            return delegate.submitEvidence(evidence)
        }

        val statusReads = AtomicInteger()

        override suspend fun status(networkCaseId: String): SchemeResult<SchemeDispute> {
            statusReads.incrementAndGet()
            delay(HOLD_MS)
            return delegate.status(networkCaseId)
        }
    }

    @Inject
    lateinit var tokenisation: SlowTokenisation

    @Inject
    lateinit var disputes: SlowDisputes

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @Test
    fun `two simultaneous provisions with one key call the network once and never 500`() {
        val before = tokenisation.provisions.get()
        val body = """{"cardId":"$ACTIVE_CARD","requestorId":"wallet-apple","requestorLabel":"Apple Pay"}"""

        val responses = race { post("/api/v1/card-tokens", "it-tok-race-1", body) }

        assertThat(responses.map { it.statusCode }).doesNotContain(INTERNAL_ERROR)
        assertThat(responses.map { it.statusCode }).allMatch { it == CREATED || it == CONFLICT }
        assertThat(responses.count { it.statusCode == CREATED }).isGreaterThanOrEqualTo(1)
        responses.filter { it.statusCode == CONFLICT }
            .forEach { assertThat(it.asString()).contains("IDEMPOTENCY_REQUEST_IN_PROGRESS") }
        // The discriminating assertion: without the reservation both reach the network.
        assertThat(tokenisation.provisions.get() - before).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM card_network_tokens WHERE idempotency_key = 'it-tok-race-1'"))
            .isEqualTo(1)
        assertThat(
            string(
                "SELECT state FROM card_lifecycle_idempotency WHERE reservation_key = 'TOKEN_PROVISION:it-tok-race-1'",
            ),
        ).isEqualTo("COMPLETED")

        // A later retry REPLAYS the winner's token — same id, no network call.
        val winnerId = responses.first { it.statusCode == CREATED }.path<String>("id")
        val replay = post("/api/v1/card-tokens", "it-tok-race-1", body)
        assertThat(replay.statusCode).isEqualTo(CREATED)
        assertThat(replay.path<String>("id")).isEqualTo(winnerId)
        assertThat(tokenisation.provisions.get() - before).isEqualTo(1)
    }

    @Test
    fun `the same key with a different body is 409 IDEMPOTENCY_KEY_REUSED`() {
        post(
            "/api/v1/card-tokens",
            "it-tok-reuse-1",
            """{"cardId":"$ACTIVE_CARD","requestorId":"wallet-apple","requestorLabel":"Apple Pay"}""",
        ).then().statusCode(CREATED)

        post(
            "/api/v1/card-tokens",
            "it-tok-reuse-1",
            """{"cardId":"$ACTIVE_CARD","requestorId":"wallet-google","requestorLabel":"Google Pay"}""",
        ).then().statusCode(CONFLICT)
            .extract().asString().also { assertThat(it).contains("IDEMPOTENCY_KEY_REUSED") }
    }

    @Test
    fun `a blocked card is never tokenised and the key stays free`() {
        val before = tokenisation.provisions.get()
        val response = post(
            "/api/v1/card-tokens",
            "it-tok-blocked-1",
            """{"cardId":"$BLOCKED_CARD","requestorId":"wallet-apple","requestorLabel":"Apple Pay"}""",
        )

        assertThat(response.statusCode).isEqualTo(CONFLICT)
        assertThat(response.path<String>("reason")).isEqualTo("CARD_NOT_ACTIVE")
        assertThat(tokenisation.provisions.get()).isEqualTo(before)
        assertThat(
            count("SELECT count(*) FROM card_lifecycle_idempotency WHERE reservation_key LIKE '%it-tok-blocked-1'"),
        )
            .isZero()
    }

    @Test
    fun `an unreachable card-issuance is a 503, an unknown card a 404`() {
        val unreachable = post(
            "/api/v1/card-tokens",
            "it-tok-503-1",
            """{"cardId":"$UNREACHABLE_CARD","requestorId":"wallet-apple","requestorLabel":"Apple Pay"}""",
        )
        assertThat(unreachable.statusCode).isEqualTo(SERVICE_UNAVAILABLE)
        assertThat(unreachable.path<String>("reason")).isEqualTo("ISSUER_UNAVAILABLE")

        val unknown = post(
            "/api/v1/card-tokens",
            "it-tok-404-1",
            """{"cardId":"${UUID.randomUUID()}","requestorId":"wallet-apple","requestorLabel":"Apple Pay"}""",
        )
        assertThat(unknown.statusCode).isEqualTo(NOT_FOUND)
        assertThat(unknown.path<String>("reason")).isEqualTo("CARD_NOT_FOUND")
        assertThat(unknown.path<String>("message")).doesNotContain("could not be reached")
    }

    @Test
    fun `two simultaneous dispute opens with one key open one chargeback`() {
        val authorizationId = clearedAuthorization("it-dsp-race")
        val before = disputes.opens.get()
        val body = """{"authorizationId":"$authorizationId","reasonCode":"10.4",""" +
            """"amountMinorUnits":2000,"currencyCode":"CZK"}"""

        val responses = race { post("/api/v1/card-disputes", "it-dsp-race-1", body) }

        assertThat(responses.map { it.statusCode }).allMatch { it == CREATED || it == CONFLICT }
        assertThat(disputes.opens.get() - before).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM card_dispute_cases WHERE authorization_id = '$authorizationId'"))
            .isEqualTo(1)
    }

    @Test
    fun `a dispute in another currency is 409 CURRENCY_MISMATCH`() {
        val authorizationId = clearedAuthorization("it-dsp-ccy")
        val response = post(
            "/api/v1/card-disputes",
            "it-dsp-ccy-1",
            """{"authorizationId":"$authorizationId","reasonCode":"10.4","amountMinorUnits":2000,"currencyCode":"EUR"}""",
        )

        assertThat(response.statusCode).isEqualTo(CONFLICT)
        assertThat(response.path<String>("reason")).isEqualTo("CURRENCY_MISMATCH")
    }

    @Test
    fun `evidence needs a key, replays a retry, and every filing is kept in the history`() {
        val authorizationId = clearedAuthorization("it-evd")
        val disputeId = post(
            "/api/v1/card-disputes",
            "it-evd-open",
            """{"authorizationId":"$authorizationId","reasonCode":"10.4","amountMinorUnits":2000,"currencyCode":"CZK"}""",
        ).then().statusCode(CREATED).extract().path<String>("id")

        given().contentType(ContentType.JSON).body("""{"documentReference":"doc-0","note":null}""")
            .post("/api/v1/card-disputes/$disputeId/evidence")
            .then().statusCode(BAD_REQUEST)

        val before = disputes.evidence.get()
        val first = """{"documentReference":"doc-1","note":"receipt"}"""
        val raced = race { post("/api/v1/card-disputes/$disputeId/evidence", "it-evd-1", first) }
        assertThat(raced.map { it.statusCode }).allMatch { it == OK || it == CONFLICT }
        post("/api/v1/card-disputes/$disputeId/evidence", "it-evd-1", first).then().statusCode(OK)
        assertThat(disputes.evidence.get() - before).isEqualTo(1)

        post("/api/v1/card-disputes/$disputeId/evidence", "it-evd-2", """{"documentReference":"doc-2","note":null}""")
            .then().statusCode(OK)

        assertThat(count("SELECT count(*) FROM card_dispute_evidence WHERE dispute_id = '$disputeId'")).isEqualTo(2)
        val history = given().get("/api/v1/card-disputes/$disputeId/evidence").then().statusCode(OK).extract()
        assertThat(history.path<List<String>>("evidence.documentReference")).containsExactly("doc-1", "doc-2")
        assertThat(
            given().get("/api/v1/card-disputes/$disputeId").then().statusCode(OK).extract()
                .path<String>("evidenceReference"),
        ).isEqualTo("doc-2")
    }

    @Test
    fun `a token status change needs a key, reaches the network once per key, and refuses a reused key`() {
        val tokenReference = post(
            "/api/v1/card-tokens",
            "it-tst-provision",
            """{"cardId":"$ACTIVE_CARD","requestorId":"wallet-apple","requestorLabel":"Apple Pay"}""",
        ).then().statusCode(CREATED).extract().path<String>("tokenReference")
        val path = "/api/v1/card-tokens/$tokenReference/status"
        val suspend = """{"status":"SUSPENDED"}"""

        // Missing header: a 400, never a 500, and the network is not asked.
        val before = tokenisation.statusChanges.get()
        given().contentType(ContentType.JSON).body(suspend).post(path).then().statusCode(BAD_REQUEST)
        assertThat(tokenisation.statusChanges.get()).isEqualTo(before)

        // Two simultaneous same-key requests: exactly one reaches the network.
        val raced = race { post(path, "it-tst-1", suspend) }
        assertThat(raced.map { it.statusCode }).allMatch { it == OK || it == CONFLICT }
        raced.filter { it.statusCode == CONFLICT }
            .forEach { assertThat(it.asString()).contains("IDEMPOTENCY_REQUEST_IN_PROGRESS") }
        assertThat(tokenisation.statusChanges.get() - before).isEqualTo(1)
        assertThat(
            string(
                "SELECT state FROM card_lifecycle_idempotency WHERE reservation_key = 'TOKEN_STATUS_CHANGE:it-tst-1'",
            ),
        ).isEqualTo("COMPLETED")

        // A retry replays the same result without a second network call.
        val replay = post(path, "it-tst-1", suspend)
        assertThat(replay.statusCode).isEqualTo(OK)
        assertThat(replay.path<String>("status")).isEqualTo("SUSPENDED")
        assertThat(tokenisation.statusChanges.get() - before).isEqualTo(1)

        // Same key, different body: 409 IDEMPOTENCY_KEY_REUSED, network untouched.
        post(path, "it-tst-1", """{"status":"ACTIVE"}""").then().statusCode(CONFLICT)
            .extract().asString().also { assertThat(it).contains("IDEMPOTENCY_KEY_REUSED") }
        assertThat(tokenisation.statusChanges.get() - before).isEqualTo(1)
    }

    @Test
    fun `a dispute refresh needs a key, reaches the network once per key, and refuses a reused key`() {
        val authorizationId = clearedAuthorization("it-rfr")
        val disputeId = post(
            "/api/v1/card-disputes",
            "it-rfr-open",
            """{"authorizationId":"$authorizationId","reasonCode":"10.4","amountMinorUnits":2000,"currencyCode":"CZK"}""",
        ).then().statusCode(CREATED).extract().path<String>("id")
        val path = "/api/v1/card-disputes/$disputeId/refresh"

        val before = disputes.statusReads.get()
        given().post(path).then().statusCode(BAD_REQUEST)
        assertThat(disputes.statusReads.get()).isEqualTo(before)

        val raced = race { postEmpty(path, "it-rfr-1") }
        assertThat(raced.map { it.statusCode }).allMatch { it == OK || it == CONFLICT }
        raced.filter { it.statusCode == CONFLICT }
            .forEach { assertThat(it.asString()).contains("IDEMPOTENCY_REQUEST_IN_PROGRESS") }
        assertThat(disputes.statusReads.get() - before).isEqualTo(1)
        assertThat(
            string("SELECT state FROM card_lifecycle_idempotency WHERE reservation_key = 'DISPUTE_REFRESH:it-rfr-1'"),
        ).isEqualTo("COMPLETED")

        val replay = postEmpty(path, "it-rfr-1")
        assertThat(replay.statusCode).isEqualTo(OK)
        assertThat(replay.path<String>("id")).isEqualTo(disputeId)
        assertThat(disputes.statusReads.get() - before).isEqualTo(1)

        // The same key against ANOTHER case is a different request.
        val otherAuth = clearedAuthorization("it-rfr2")
        val otherId = post(
            "/api/v1/card-disputes",
            "it-rfr2-open",
            """{"authorizationId":"$otherAuth","reasonCode":"10.4","amountMinorUnits":2000,"currencyCode":"CZK"}""",
        ).then().statusCode(CREATED).extract().path<String>("id")
        postEmpty("/api/v1/card-disputes/$otherId/refresh", "it-rfr-1").then().statusCode(CONFLICT)
            .extract().asString().also { assertThat(it).contains("IDEMPOTENCY_KEY_REUSED") }
        assertThat(disputes.statusReads.get() - before).isEqualTo(1)
    }

    /** Fires [request] twice from two threads released by one latch, and returns both responses. */
    private fun race(request: () -> Response): List<Response> {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = (1..2).map {
                pool.submit<Response> {
                    start.await()
                    request()
                }
            }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun post(path: String, key: String, body: String): Response = given()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", key)
        .body(body)
        .post(path)

    private fun postEmpty(path: String, key: String): Response = given().header("Idempotency-Key", key).post(path)

    /** An authorisation on [ACTIVE_CARD] with 5000 minor units cleared and an acquirer reference. */
    private fun clearedAuthorization(prefix: String): String {
        val id = post(
            "/api/v1/card-authorizations",
            "$prefix-auth",
            """
            {"cardId":"$ACTIVE_CARD","amountMinorUnits":5000,"currencyCode":"CZK","channel":"ONLINE",
             "mcc":"5411","merchantName":"Shop","merchantCountry":"CZ","networkReference":"$prefix-acq"}
            """.trimIndent(),
        ).then().statusCode(CREATED).extract().path<String>("id")
        post(
            "/api/v1/card-authorizations/$id/clearing",
            "$prefix-clear",
            """{"amountMinorUnits":5000,"currencyCode":"CZK"}""",
        )
            .then().statusCode(OK)
        return id
    }

    private fun <T> query(sql: String, read: (ResultSet) -> T): T =
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { connection ->
            connection.createStatement().executeQuery(sql).use { rs ->
                rs.next()
                read(rs)
            }
        }

    private fun count(sql: String): Long = query(sql) { it.getLong(1) }

    private fun string(sql: String): String = query(sql) { it.getString(1) }

    companion object {
        val ACTIVE_CARD: UUID = UUID.fromString("1c1c1c1c-2d2d-4e3e-8f4f-5a5a5a5a5a5a")
        val BLOCKED_CARD: UUID = UUID.fromString("2c2c2c2c-3d3d-4e4e-8f5f-6a6a6a6a6a6a")
        val UNREACHABLE_CARD: UUID = UUID.fromString("3c3c3c3c-4d4d-4e5e-8f6f-7a7a7a7a7a7a")
        val ACCOUNT: UUID = UUID.fromString("4c4c4c4c-5d5d-4e6e-8f7f-8a8a8a8a8a8a")
        val PARTY: UUID = UUID.fromString("5c5c5c5c-6d6d-4e7e-8f8f-9a9a9a9a9a9a")
        const val HOLD_MS = 1_500L
        const val OK = 200
        const val CREATED = 201
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val INTERNAL_ERROR = 500
        const val SERVICE_UNAVAILABLE = 503
    }
}
