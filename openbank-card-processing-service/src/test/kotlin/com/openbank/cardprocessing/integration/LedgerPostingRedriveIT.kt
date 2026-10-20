// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.integration

import com.openbank.cardprocessing.application.port.out.LedgerPostingPort
import com.openbank.cardprocessing.application.port.out.PostingOutcome
import com.openbank.cardprocessing.application.port.out.PostingResult
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.infrastructure.client.CardClearingLedgerKey
import com.openbank.cardprocessing.support.capturePostingLogs
import io.micrometer.core.instrument.MeterRegistry
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
import kotlinx.coroutines.delay
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ledger-posting re-driver on its REAL cron, against a real database (V5, #13228).
 *
 * The scheduler is re-enabled and its cron shrunk by a `@TestProfile`, because a test that calls the
 * `@Scheduled` method directly supplies the very Vert.x context the scheduler does not, and so cannot
 * see the defect that matters (#2148). The "books" are simulated by [ScriptedBooks]: like
 * transaction-service it books a debit once per idempotency key and answers a repeat from the record,
 * so "the debit is not doubled" is measured on the books, not assumed.
 *
 * What each test would prove false if the re-driver were missing:
 *  - UNKNOWN after a lost answer is completed (without it the row stays UNKNOWN for ever);
 *  - a row pushed back to unposted and re-driven again does not book a second debit;
 *  - a posting that keeps failing stops at the ceiling, is KEPT (not dropped) and is counted as
 *    exhausted - the alert signal.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.cardprocessing.it.PostgresTestResource::class)
@TestProfile(LedgerPostingRedriveIT.Profile::class)
@TestSecurity(user = "card-processing-it", roles = ["ROLE_OPERATOR"])
class LedgerPostingRedriveIT {

    class Profile : QuarkusTestProfile {
        // Literal values only: a profile loads in a different classloader from the test class.
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.card-processing.posting-redrive-cron" to "*/2 * * * * ?",
            "openbank.card-processing.posting-backoff-seconds" to "3",
            "openbank.card-processing.posting-backoff-max-seconds" to "3",
            "openbank.card-processing.posting-max-attempts" to "4",
            "openbank.card-processing.posting-first-grace-seconds" to "2",
            "openbank.card-processing.posting-lease-seconds" to "4",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(
            CardAuthorizationOutboxIT.StubIssuer::class.java,
            ScriptedBooks::class.java,
        )
    }

    /**
     * `@Alternative` WITHOUT `@Priority` (see the sibling ITs): enabled by the profile only.
     *
     * The script is chosen by the clearing key. `books` is what transaction-service would hold: one
     * debit per ledger idempotency key, however many times it is presented.
     */
    @Alternative
    @ApplicationScoped
    class ScriptedBooks : LedgerPostingPort {
        val books = ConcurrentHashMap<String, Int>()
        val attempts = ConcurrentHashMap<String, AtomicInteger>()

        override suspend fun postClearedSpend(
            authorization: CardAuthorization,
            clearedAmountMinorUnits: Long,
            idempotencyKey: String,
        ): PostingResult {
            val n = attempts.computeIfAbsent(idempotencyKey) { AtomicInteger() }.incrementAndGet()
            val ledgerKey = CardClearingLedgerKey.of(authorization.id, idempotencyKey)
            return when {
                idempotencyKey.startsWith("it-rd-private-error") && n > 1 ->
                    throw IllegalStateException(
                        "synthetic-private-exception",
                        IllegalArgumentException("synthetic-private-cause"),
                    )

                idempotencyKey.startsWith("it-rd-private-failed") ->
                    PostingResult(PostingOutcome.FAILED, null, "synthetic-private-provider-body")

                idempotencyKey.startsWith("it-rd-private-unknown") ||
                    idempotencyKey.startsWith("it-rd-private-error") ->
                    PostingResult(PostingOutcome.UNKNOWN, null, "synthetic-private-provider-body")

                idempotencyKey.startsWith("it-rd-private-posted") -> {
                    books.merge(ledgerKey, 1) { a, _ -> a }
                    PostingResult(PostingOutcome.POSTED, authorization.id, "synthetic-private-provider-body")
                }

                // A SLOW inline attempt that outlives the re-driver's claim and answers late with a
                // failure: the stale result must not overwrite the newer attempt's POSTED.
                idempotencyKey.startsWith("it-rd-stale") && n == 1 -> {
                    delay(STALE_ATTEMPT_MILLIS)
                    PostingResult(PostingOutcome.FAILED, null, "late failure from a superseded attempt")
                }

                idempotencyKey.startsWith("it-rd-fail") -> PostingResult(PostingOutcome.FAILED, null, "ledger refused")

                // The debit IS booked on the first attempt, but the answer is lost: exactly the case
                // where only the idempotency key stands between a retry and a double debit.
                idempotencyKey.startsWith("it-rd-unknown") && n == 1 -> {
                    books.merge(ledgerKey, 1) { a, _ -> a }
                    PostingResult(PostingOutcome.UNKNOWN, null, "answer unreadable")
                }

                else -> {
                    books.merge(ledgerKey, 1) { a, _ -> a }
                    PostingResult(PostingOutcome.POSTED, authorization.id, "COMPLETED")
                }
            }
        }
    }

    @Inject
    lateinit var ledger: ScriptedBooks

    @Inject
    lateinit var registry: MeterRegistry

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    private fun authorize(key: String): String = given()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", key)
        .body(
            """
            {"cardId":"${CardAuthorizationOutboxIT.StubIssuer.KNOWN_CARD}","amountMinorUnits":30000,
             "currencyCode":"CZK","channel":"ONLINE","networkReference":"$key"}
            """.trimIndent(),
        )
        .post("/api/v1/card-authorizations")
        .then()
        .statusCode(CREATED)
        .extract().path("id")

    private fun clear(id: String, key: String) = given()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", key)
        .body("""{"amountMinorUnits":10000,"currencyCode":"CZK"}""")
        .post("/api/v1/card-authorizations/$id/clearing")
        .then()
        .statusCode(OK)

    private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T =
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { c ->
            c.createStatement().executeQuery(sql).use { rs ->
                rs.next()
                read(rs)
            }
        }

    private fun update(sql: String) {
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use {
            it.createStatement().executeUpdate(sql)
        }
    }

    private fun status(key: String) =
        query("SELECT posting_status FROM card_clearings WHERE idempotency_key = '$key'") { it.getString(1) }

    private fun attempts(key: String) =
        query("SELECT posting_attempts FROM card_clearings WHERE idempotency_key = '$key'") { it.getInt(1) }

    private fun await(ready: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return ready()
    }

    private fun books(authorizationId: String, key: String) =
        ledger.books[CardClearingLedgerKey.of(java.util.UUID.fromString(authorizationId), key)] ?: 0

    @Test
    fun `an UNKNOWN posting after a lost answer is completed by the cron and the debit is booked once`() {
        val key = "it-rd-unknown-1"
        val id = authorize(key)
        clear(id, key)
        // The inline attempt happened before the response was returned: unreadable, so UNKNOWN.
        assertThat(status(key)).isEqualTo("UNKNOWN")

        assertThat(await { status(key) == "POSTED" })
            .describedAs("the scheduled re-driver must reach the row (HR000068 off the Vert.x context otherwise)")
            .isTrue()

        assertThat(books(id, key)).describedAs("the debit exists, and exactly once").isEqualTo(1)
        assertThat(ledger.attempts[key]!!.get()).isEqualTo(2)
        assertThat(attempts(key)).isEqualTo(2)
        Thread.sleep(SETTLE_MILLIS)
        assertThat(ledger.attempts[key]!!.get()).describedAs("a POSTED row is never re-driven").isEqualTo(2)
    }

    @Test
    fun `re-driving a row again does not double the debit`() {
        val key = "it-rd-ok-1"
        val id = authorize(key)
        clear(id, key)
        assertThat(status(key)).isEqualTo("POSTED")
        val before = ledger.attempts[key]!!.get()

        // Push it back to unposted, due now, as if the recorded outcome had been lost: the re-driver
        // posts it a SECOND and a third time under the same key.
        repeat(2) { round ->
            update(
                "UPDATE card_clearings SET posting_status = 'UNKNOWN', posting_next_attempt_at = now() " +
                    "WHERE idempotency_key = '$key'",
            )
            assertThat(await { status(key) == "POSTED" && ledger.attempts[key]!!.get() == before + round + 1 })
                .describedAs("round %d re-driven", round)
                .isTrue()
        }

        assertThat(books(id, key)).describedAs("three presentations, one debit").isEqualTo(1)
    }

    @Test
    fun `a posting that keeps failing stops at the ceiling, is kept, and is counted as exhausted`() {
        val key = "it-rd-fail-1"
        val id = authorize(key)
        clear(id, key)

        assertThat(await { attempts(key) >= MAX_ATTEMPTS })
            .describedAs("backoff 3s, ceiling 4 attempts")
            .isTrue()
        Thread.sleep(SETTLE_MILLIS)

        assertThat(attempts(key)).describedAs("no attempt past the ceiling").isEqualTo(MAX_ATTEMPTS)
        assertThat(status(key)).describedAs("kept as FAILED, never dropped or marked POSTED").isEqualTo("FAILED")
        assertThat(query("SELECT count(*) FROM card_clearings WHERE idempotency_key = '$key'") { it.getInt(1) })
            .isEqualTo(1)
        assertThat(books(id, key)).isZero()
        assertThat(
            await {
                registry.find("openbank.card.processing.ledger.posting.exhausted").gauge()?.value()?.let {
                    it >= 1.0
                } ==
                    true
            },
        ).describedAs("the exhausted gauge is the alert signal").isTrue()
    }

    @Test
    fun `a slow superseded attempt cannot overwrite a newer POSTED nor count as an attempt`() {
        val key = "it-rd-stale-1"
        val id = authorize(key)
        // Blocks ~10s inside the inline attempt. Meanwhile the row is due (2s grace), the cron claims it
        // and its attempt POSTS; the inline attempt then answers FAILED, late, holding a dead claim.
        clear(id, key)

        assertThat(await { status(key) == "POSTED" }).isTrue()
        assertThat(ledger.attempts[key]!!.get()).describedAs("inline + re-driver").isEqualTo(2)
        assertThat(status(key)).describedAs("POSTED is terminal: the late FAILED is ignored").isEqualTo("POSTED")
        assertThat(attempts(key)).describedAs("only the valid attempt counted").isEqualTo(1)
        assertThat(books(id, key)).isEqualTo(1)
        Thread.sleep(SETTLE_MILLIS)
        assertThat(status(key)).isEqualTo("POSTED")
        assertThat(attempts(key)).isEqualTo(1)
    }

    @Test
    fun `HTTP clearings persist only controlled diagnostics while redrive preserves accounting`() {
        val logs = capturePostingLogs {
            assertPrivatePosting("failed", "FAILED", "POSTING_FAILED")
            assertPrivatePosting("unknown", "UNKNOWN", "COMPLETION_UNPROVEN")
            assertPrivatePosting("posted", "POSTED", null)
            val key = "it-rd-private-error-synthetic-private-key"
            val id = authorize(key)
            clear(id, key)
            assertThat(status(key)).isEqualTo("UNKNOWN")
            assertThat(await { error(key) == "REDRIVE_FAILED" }).isTrue()
            assertThat(status(key)).isEqualTo("FAILED")
            assertThat(attempts(key)).isGreaterThanOrEqualTo(2)
            assertThat(books(id, key)).isZero()
        }
        assertThat(logs).isNotEmpty()
        assertThat(logs.map { it.text }.joinToString()).doesNotContain(
            "synthetic-private-provider-body",
            "synthetic-private-exception",
            "synthetic-private-cause",
            "synthetic-private-key",
        )
        assertThat(logs.mapNotNull { it.thrown }).isEmpty()
    }

    private fun assertPrivatePosting(suffix: String, expectedStatus: String, expectedError: String?) {
        val key = "it-rd-private-$suffix-synthetic-private-key"
        val id = authorize(key)
        clear(id, key)
        assertThat(status(key)).isEqualTo(expectedStatus)
        assertThat(error(key)).isEqualTo(expectedError)
        assertThat(attempts(key)).isEqualTo(1)
        assertThat(books(id, key)).isEqualTo(if (expectedStatus == "POSTED") 1 else 0)
    }

    private fun error(key: String): String? =
        query("SELECT posting_last_error FROM card_clearings WHERE idempotency_key = '$key'") { it.getString(1) }

    private companion object {
        const val STALE_ATTEMPT_MILLIS = 10_000L
        const val CREATED = 201
        const val OK = 200
        const val MAX_ATTEMPTS = 4
        const val BUDGET_NANOS = 90_000_000_000L
        const val POLL_MILLIS = 250L
        const val SETTLE_MILLIS = 6_000L
    }
}
