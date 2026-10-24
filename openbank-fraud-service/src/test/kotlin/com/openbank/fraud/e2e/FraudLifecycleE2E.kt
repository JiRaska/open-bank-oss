// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.e2e

import com.openbank.fraud.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * End-to-end journeys of the fraud scoring surface over the real HTTP plane against real Postgres
 * + Valkey, with the async transaction-signal Kafka channel switched to in-memory and driven as the
 * deployed wiring drives it. The deterministic rule engine is unit-tested in isolation; this suite
 * pins what only the wired service shows:
 *
 *  - **The verdict ladder over real HTTP**: a baseline payment is `ALLOW` with the pinned rule-set
 *    version; a large single transaction REVIEWs at the per-currency threshold (CZK and EUR trip
 *    their own caps, not one global figure); an unmapped currency fails CLOSED — even a trivial
 *    amount is REVIEWed for a human, never silently waived through.
 *  - **The signal plane learns**: a `transaction.initiated` signal records payee history, so the
 *    same first-payment-sized amount is REVIEWed for a stranger (`new-payee-high-amount`) but
 *    ALLOWED once the account has paid that counterparty; a rolling-hour burst of 10+ transactions
 *    trips the velocity cap (`velocity-h1-cap`).
 *  - **The review queue is the analyst surface**: REVIEW verdicts land in `GET /review-queue`,
 *    readable by the operator and compliance roles alike.
 *  - **Guards stay client errors**: a lowercase currency, a Jackson-coerced non-string currency
 *    (`{"currency": false}` deserialises to `"false"` and must not reach the `varchar(3)` column),
 *    and a blank rail are all 400; an unauthenticated caller is 401; `ROLE_VIEWER` is refused 403
 *    on the review queue.
 *
 * Signals are published to the in-memory source exactly once per aggregate id — the consumer's
 * idempotent upserts are its own integration concern (BootIT over a real broker); here the channel
 * is the wiring, not the subject. Outbox dispatch is disabled: no broker is claimed.
 */
@QuarkusTest
// restrictToAnnotatedClass: without it a @QuarkusTestResource applies to EVERY @QuarkusTest in
// the JVM — the in-memory switch below would hijack TransactionSignalConsumerBootIT's real-Kafka
// channel and that IT's consumer would never subscribe (measured: BootIT's group-id assertion
// then sees an empty group list and fails).
@QuarkusTestResource(PostgresRedisTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(FraudLifecycleE2E.InMemoryKafkaResource::class, restrictToAnnotatedClass = true)
class FraudLifecycleE2E {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("transaction-signal") +
                mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun stop() = InMemoryConnector.clear()
    }

    @Any
    @Inject
    lateinit var connector: InMemoryConnector

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `the verdict ladder runs over real http from baseline allow to fail-closed currency`() {
        val account = UUID.randomUUID()
        val allowed = score("1250.00", "CZK", account, counterparty = UUID.randomUUID())
        assertThat(allowed["verdict"]).isEqualTo("ALLOW")
        assertThat(allowed["score"] as Int).isEqualTo(0)
        assertThat(allowed["ruleVersion"]).isEqualTo("v4")
        assertThat(allowed["reasons"] as List<*>).containsExactly("baseline-allow")

        // Per-currency thresholds, not one global figure: CZK 500k and EUR 20k are the same tier.
        val largeCzk = score("600000.00", "CZK", UUID.randomUUID())
        assertThat(largeCzk["verdict"]).isEqualTo("REVIEW")
        assertThat(largeCzk["reasons"] as List<*>).contains("large-single-transaction")
        val largeEur = score("25000.00", "EUR", UUID.randomUUID())
        assertThat(largeEur["verdict"]).isEqualTo("REVIEW")
        assertThat(largeEur["reasons"] as List<*>).contains("large-single-transaction")

        // Unmapped currency fails CLOSED: even a trivial amount is flagged for a human.
        val usd = score("100.00", "USD", UUID.randomUUID())
        assertThat(usd["verdict"]).isEqualTo("REVIEW")
        assertThat(usd["reasons"] as List<*>).contains("large-single-transaction-unmapped-currency")
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `review verdicts land in the analyst queue`() {
        // Seed this test's own REVIEW row — JUnit5 runs methods in an arbitrary order, so the
        // queue must not depend on the verdict-ladder test having run first. The operator role
        // is the analyst: it alone can both score (ROLE_API/OPERATOR/ADMIN) and read the queue
        // (ROLE_COMPLIANCE/OPERATOR/ADMIN) within one security context.
        val flagged = score("600000.00", "CZK", UUID.randomUUID(), counterparty = UUID.randomUUID())
        assertThat(flagged["verdict"]).isEqualTo("REVIEW")
        given().`when`().get("/api/v1/fraud/review-queue")
            .then().statusCode(200)
            .body("size()", greaterThan(0))
            .body("verdict", hasItem("REVIEW"))
            .body("[0].verdict", equalTo("REVIEW"))
            .body("[0].ruleVersion", equalTo("v4"))
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `the signal plane turns a stranger payee review into allow on history`() {
        val account = UUID.randomUUID()
        val counterparty = UUID.randomUUID()

        // A different account with no history to this payee is a stranger: the combination rule
        // fires at half the single-payment threshold.
        val stranger = score("300000.00", "CZK", UUID.randomUUID(), counterparty)
        assertThat(stranger["verdict"]).isEqualTo("REVIEW")
        assertThat(stranger["reasons"] as List<*>).contains("new-payee-high-amount")

        // One transaction.initiated signal records the payee for this account; the same amount now
        // sits under the single-payment threshold and the payee is known — the verdict changes
        // without any rule changing. The consumer applies the signal on its own schedule, so poll.
        sendSignal(account, counterparty)
        val known = scoreEventually("300000.00", "CZK", account, counterparty, expected = "ALLOW")
        assertThat(known["verdict"]).isEqualTo("ALLOW")
        assertThat(known["reasons"] as List<*>).containsExactly("baseline-allow")
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `a rolling-hour burst trips the velocity cap`() {
        val account = UUID.randomUUID()
        // 12 transactions inside the rolling H1 window — the cap is 10 inclusive.
        repeat(12) { sendSignal(account, null) }
        val verdict = scoreEventually("100.00", "CZK", account, counterparty = null, expected = "REVIEW")
        assertThat(verdict["reasons"] as List<*>).contains("velocity-h1-cap")
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `malformed scoring requests stay client errors`() {
        val account = UUID.randomUUID()
        // Lowercase is not ISO 4217 upper-case.
        scoreRaw("""{"amount":"10.00","currency":"czk","rail":"SEPA"}""").then().statusCode(400)
        // Jackson coerces a non-string scalar into a String: {"currency": false} -> "false".
        // The HTTP boundary must reject it as a 400, not let it reach the varchar(3) column.
        scoreRaw("""{"amount":10.00,"currency":false,"rail":"SEPA"}""").then().statusCode(400)
        scoreRaw("""{"amount":"10.00","currency":"CZK","rail":" "}""").then().statusCode(400)
        // Sanity: a well-formed request still scores.
        score("10.00", "CZK", account, counterparty = null)
    }

    @Test
    fun `an unauthenticated caller is refused 401 and viewer is refused 403 on the queue`() {
        given().contentType("application/json")
            .body("""{"amount":"10.00","currency":"CZK","rail":"SEPA"}""")
            .`when`().post("/api/v1/fraud/score")
            .then().statusCode(401)

        given().`when`().get("/api/v1/fraud/review-queue").then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "e2e-viewer-1", roles = ["ROLE_VIEWER"])
    fun `viewer reads neither the queue nor scores`() {
        given().`when`().get("/api/v1/fraud/review-queue").then().statusCode(403)
        given().contentType("application/json")
            .body("""{"amount":"10.00","currency":"CZK","rail":"SEPA"}""")
            .`when`().post("/api/v1/fraud/score")
            .then().statusCode(403)
    }

    // ---------- REST and signal journey helpers ----------

    private fun scoreRaw(body: String) = given()
        .contentType("application/json").body(body)
        .`when`().post("/api/v1/fraud/score")

    private fun score(
        amount: String,
        currency: String,
        accountId: UUID,
        counterparty: UUID? = null,
    ): Map<String, kotlin.Any?> = scoreRaw(
        """
            {"amount":"$amount","currency":"$currency","rail":"SEPA_INSTANT",
             "accountId":"$accountId"${if (counterparty != null) ",\"counterpartyId\":\"$counterparty\"" else ""}}
        """.trimIndent(),
    ).then().statusCode(200).extract().body().jsonPath().getMap<String, kotlin.Any?>("")

    /**
     * Score with a deadline, waiting for the ALLOW the signal plane enables: the async consumer
     * applies the signal on its own schedule, so the first polls legitimately still see the
     * pre-signal verdict. Only the final answer is asserted; intermediate scores are ordinary rows
     * in the test database.
     */
    private fun scoreEventually(
        amount: String,
        currency: String,
        accountId: UUID,
        counterparty: UUID?,
        expected: String,
    ): Map<String, kotlin.Any?> {
        var last: Map<String, kotlin.Any?> = emptyMap()
        repeat(20) {
            last = score(amount, currency, accountId, counterparty)
            if (last["verdict"] == expected) return last
            Thread.sleep(300)
        }
        return last
    }

    private fun sendSignal(sourceAccountId: UUID, targetAccountId: UUID?) {
        val payload = """
            {"aggregateId":"${UUID.randomUUID()}","sourceAccountId":"$sourceAccountId",
             ${if (targetAccountId != null) "\"targetAccountId\":\"$targetAccountId\"," else ""}
             "amount":100.00,"currencyCode":"CZK","occurredAt":"${Instant.now()}"}
        """.trimIndent()
        val source: InMemorySource<String> = connector.source("transaction-signal")
        source.send(payload)
    }
}
