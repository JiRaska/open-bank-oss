// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.integration

import com.openbank.cardprocessing.application.port.out.LedgerPostingPort
import com.openbank.cardprocessing.application.port.out.PostingOutcome
import com.openbank.cardprocessing.application.port.out.PostingResult
import com.openbank.cardprocessing.domain.model.CardAuthorization
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A clearing presentment is applied once per clearing key — over real HTTP, against a real
 * database, with the ledger posting attempts counted.
 *
 * Before the guard, the key on a clearing was carried and never looked up: a repeated presentment
 * that still fitted inside the remaining hold was applied again — a second hold decrement and a
 * second debit of the cardholder. A mocked repository cannot show either half of the fix: the
 * lookup is SQL, and the concurrent case is decided by the UNIQUE constraint on
 * `card_clearings (authorization_id, idempotency_key)`, which only a real Postgres enforces.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.cardprocessing.it.PostgresTestResource::class)
@TestProfile(CardClearingIdempotencyIT.Profile::class)
@TestSecurity(user = "card-processing-it", roles = ["ROLE_OPERATOR"])
class CardClearingIdempotencyIT {

    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(
            CardAuthorizationOutboxIT.StubIssuer::class.java,
            CountingLedger::class.java,
        )
    }

    /** Counts posting attempts per authorisation. Scoped by the profile, never `@Priority` (see the sibling IT). */
    @Alternative
    @ApplicationScoped
    class CountingLedger : LedgerPostingPort {
        val attempts = ConcurrentHashMap<String, AtomicInteger>()

        override suspend fun postClearedSpend(
            authorization: CardAuthorization,
            clearedAmountMinorUnits: Long,
            idempotencyKey: String,
        ): PostingResult {
            attempts.computeIfAbsent(authorization.id.toString()) { AtomicInteger() }.incrementAndGet()
            return PostingResult(PostingOutcome.POSTED, null, null)
        }
    }

    @Inject
    lateinit var ledger: CountingLedger

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    private fun authorize(amount: Long, key: String): String = given()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", key)
        .body(
            """
            {"cardId":"${CardAuthorizationOutboxIT.StubIssuer.KNOWN_CARD}","amountMinorUnits":$amount,
             "currencyCode":"CZK","channel":"ONLINE","networkReference":"$key"}
            """.trimIndent(),
        )
        .post("/api/v1/card-authorizations")
        .then()
        .statusCode(CREATED)
        .extract().path("id")

    private fun clear(id: String, key: String, amount: Long, currency: String = "CZK") = given()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", key)
        .body("""{"amountMinorUnits":$amount,"currencyCode":"$currency"}""")
        .post("/api/v1/card-authorizations/$id/clearing")

    private fun long(sql: String): Long = DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { c ->
        c.createStatement().executeQuery(sql).use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }

    private fun cleared(id: String) =
        long("SELECT cleared_amount_minor_units FROM card_authorizations WHERE id = '$id'")

    private fun clearedEvents(id: String) =
        long("SELECT count(*) FROM card_outbox WHERE aggregate_id = '$id' AND event_type = 'card.cleared.v1'")

    private fun clearingRows(id: String) = long("SELECT count(*) FROM card_clearings WHERE authorization_id = '$id'")

    private fun postings(id: String) = ledger.attempts[id]?.get() ?: 0

    @Test
    fun `a repeated presentment of the same clearing is applied once`() {
        val id = authorize(30_000, "it-clr-dup")

        clear(id, "it-clr-dup-c1", 10_000).then().statusCode(OK).body("heldAmountMinorUnits", equalTo(20_000))
        // The retry still fits inside the remaining hold — exactly the case that used to be applied
        // again. Lower-case currency: the same request, so a replay and not a key reuse.
        clear(id, "it-clr-dup-c1", 10_000, "czk").then()
            .statusCode(OK)
            .body("status", equalTo("PARTIALLY_CLEARED"))
            .body("heldAmountMinorUnits", equalTo(20_000))

        assertThat(cleared(id)).isEqualTo(10_000)
        assertThat(clearedEvents(id)).isEqualTo(1)
        assertThat(clearingRows(id)).isEqualTo(1)
        assertThat(postings(id)).isEqualTo(1)
    }

    @Test
    fun `the same clearing key with a different amount is refused as a key reuse`() {
        val id = authorize(30_000, "it-clr-reuse")
        clear(id, "it-clr-reuse-c1", 10_000).then().statusCode(OK)

        clear(id, "it-clr-reuse-c1", 15_000).then()
            .statusCode(CONFLICT)
            .body("code", equalTo("IDEMPOTENCY_KEY_REUSED"))

        assertThat(cleared(id)).isEqualTo(10_000)
        assertThat(clearedEvents(id)).isEqualTo(1)
        assertThat(postings(id)).isEqualTo(1)
    }

    @Test
    fun `distinct clearing keys are distinct partial presentments`() {
        val id = authorize(30_000, "it-clr-partials")

        clear(id, "it-clr-partials-a", 10_000).then().statusCode(OK)
        clear(id, "it-clr-partials-b", 10_000).then().statusCode(OK).body("heldAmountMinorUnits", equalTo(10_000))

        assertThat(cleared(id)).isEqualTo(20_000)
        assertThat(clearedEvents(id)).isEqualTo(2)
        assertThat(postings(id)).isEqualTo(2)
    }

    @Test
    fun `concurrent duplicates of one clearing apply exactly once`() {
        val id = authorize(30_000, "it-clr-race")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(PARALLEL)
        val statuses = (1..PARALLEL).map {
            pool.submit<Int> {
                start.await()
                clear(id, "it-clr-race-c1", 10_000).then().extract().statusCode()
            }
        }
        start.countDown()
        val answered = statuses.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        pool.shutdown()

        // Every duplicate is answered as the one clearing, none as an error, and only one applied.
        assertThat(answered).containsOnly(OK)
        assertThat(cleared(id)).isEqualTo(10_000)
        assertThat(clearedEvents(id)).isEqualTo(1)
        assertThat(clearingRows(id)).isEqualTo(1)
        assertThat(postings(id)).isEqualTo(1)
    }

    @Test
    fun `concurrent clearings under distinct keys never clear past the hold and never lose one`() {
        // Eight presentments of 10 000 against a 30 000 hold: at most three can fit.
        val id = authorize(30_000, "it-clr-distinct")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(PARALLEL)
        val answers = (1..PARALLEL).map { n ->
            pool.submit<Int> {
                start.await()
                clear(id, "it-clr-distinct-c$n", 10_000).then().extract().statusCode()
            }
        }
        start.countDown()
        val statuses = answers.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        pool.shutdown()

        // A definite answer each: applied (200), or 409 — over the hold, or lost the race twice.
        assertThat(statuses).allMatch { it == OK || it == CONFLICT }
        val applied = statuses.count { it == OK }.toLong()
        // Every 200 is in the hold, in the events and in the books — none overwritten by another.
        assertThat(cleared(id)).isEqualTo(applied * 10_000)
        assertThat(cleared(id)).isLessThanOrEqualTo(30_000)
        assertThat(clearingRows(id)).isEqualTo(applied)
        assertThat(clearedEvents(id)).isEqualTo(applied)
        assertThat(postings(id).toLong()).isEqualTo(applied)
        assertThat(applied).isGreaterThanOrEqualTo(1)
    }

    private companion object {
        const val OK = 200
        const val CREATED = 201
        const val CONFLICT = 409
        const val PARALLEL = 8
        const val TIMEOUT_SECONDS = 60L
    }
}
