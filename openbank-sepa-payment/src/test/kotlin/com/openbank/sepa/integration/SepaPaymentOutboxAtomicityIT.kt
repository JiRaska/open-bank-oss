// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8353 — proves that `SepaPaymentRepositoryImpl` commits the `sepa_payments` row and its
 * `sepa_payment_outbox` row in **one** database transaction, on both write paths: `save` (create)
 * and `updateWithMessages` (every status transition).
 *
 * That repository's own KDoc already asserts the property — *"the evidence row must not be able to
 * commit without the transition … nor the transition without the evidence row (issue #6056 — that
 * is the state this service shipped in)"* — and until this class nothing measured it. A comment is
 * not a control.
 *
 * ### Why presence is not the property
 *
 * The house pattern (`LendingOutboxWriteIT`, `ConsentRevocationOutboxIT`) drives the flow through
 * the real REST endpoint and asserts the outbox row landed. That much is necessary — a mocked
 * repository commits nothing, and a reactive Panache repo cannot be called from a bare
 * `@QuarkusTest` thread ("No current Vertx context found"), so only a real HTTP request carries the
 * Vert.x context the write needs — but it is **not sufficient**: an implementation that persisted
 * the aggregate in one transaction and the outbox row in a second would satisfy every presence
 * assertion while having lost the property outright.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Rows
 * written by one transaction share an `xmin`; rows written by two cannot. Comparing them reads the
 * property directly. Splitting either write path into two `Panache.withTransaction` blocks turns
 * this class red, where a presence assertion stays green.
 *
 * The second test carries its own control: after a transition the row's `xmin` is the *transition's*
 * transaction, so the `created` outbox row — written earlier, by a different transaction — must
 * NOT match. One run therefore shows the comparison both matching and not matching, which is the
 * evidence that its match means something.
 *
 * The scheduled outbox dispatcher is switched off here: it UPDATEs claimed rows, and an UPDATE
 * writes a new row version under a *new* `xmin`, racing the assertion. (Production runs with
 * `openbank.outbox.dispatch-enabled: true` in `application.yaml` — the fleet default is `false`,
 * under which nothing would ever dispatch and no error would say so.)
 */
@QuarkusTest
@QuarkusTestResource(SepaPaymentOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
class SepaPaymentOutboxAtomicityIT {

    class NoDispatchInMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("events-out").toMutableMap().also {
                it["quarkus.kafka.devservices.enabled"] = "false"
                it["openbank.outbox.dispatch-enabled"] = "false"
            }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `creating a payment commits the payment row and its created event in one transaction`() {
        val paymentId = createPayment()

        val writers = writersOf(paymentId)
        assertThat(writers.keys)
            .describedAs("outbox event types written for payment %s", paymentId)
            .containsExactly(CREATED_EVENT)
        assertSameTransaction(paymentId, CREATED_EVENT)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `a status transition commits the new row version and its status-changed event in one transaction`() {
        val paymentId = createPayment()

        RestAssured.given()
            .contentType("application/json")
            .body("""{"targetStatus":"VALIDATED"}""")
            .patch("/api/v1/sepa-payments/$paymentId/status")
            .then()
            .statusCode(200)

        val writers = writersOf(paymentId)
        assertThat(writers.keys).contains(CREATED_EVENT, STATUS_CHANGED_EVENT)
        assertSameTransaction(paymentId, STATUS_CHANGED_EVENT)

        // The control: the same comparison, in the same run, on a pair that genuinely was written
        // by two different transactions. Without it, `assertSameTransaction` above could be passing
        // because every row in this database happens to share one xmin.
        val paymentXmin = writers.getValue(STATUS_CHANGED_EVENT).paymentXmin
        assertThat(writers.getValue(CREATED_EVENT).outboxXmin)
            .describedAs("the create and the transition are different transactions")
            .isNotEqualTo(paymentXmin)
    }

    /**
     * Without this, `containsExactly`/`contains` above could be reading their success out of a
     * query that never returns anything — an absence assertion that passes because everything is
     * absent. A payment id that was never written must produce no pair at all.
     */
    @Test
    fun `the atomicity query returns nothing for a payment that was never written`() {
        assertThat(writersOf(UUID.randomUUID())).isEmpty()
    }

    private fun assertSameTransaction(paymentId: UUID, eventType: String) {
        val pair = writersOf(paymentId).getValue(eventType)
        assertThat(pair.outboxXmin)
            .describedAs(
                "the sepa_payments row and its '%s' outbox row must carry the SAME Postgres xmin — " +
                    "different values mean two transactions wrote them, so one can commit without " +
                    "the other (payment xmin=%s, outbox xmin=%s)",
                eventType,
                pair.paymentXmin,
                pair.outboxXmin,
            )
            .isEqualTo(pair.paymentXmin)
    }

    private data class WriterPair(val paymentXmin: String, val outboxXmin: String)

    /** Per event type: the transaction ids (`xmin`) that wrote the aggregate row and that outbox row. */
    private fun writersOf(paymentId: UUID): Map<String, WriterPair> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT o.event_type, p.xmin::text AS payment_xmin, o.xmin::text AS outbox_xmin
            FROM sepa_payments p
            JOIN sepa_payment_outbox o ON o.aggregate_id = p.payment_id
            WHERE p.payment_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, paymentId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows else null }
                    .map { it.getString(1) to WriterPair(it.getString(2), it.getString(3)) }
                    .toMap()
            }
        }
    }

    private fun createPayment(): UUID {
        val created: Response = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", "atomicity-it-${UUID.randomUUID()}")
            .body(
                """
                {
                  "type": "SCT",
                  "debtorAccountId": "${UUID.randomUUID()}",
                  "debtorIban": "CZ6508000000192000145399",
                  "debtorName": "Alice Example",
                  "creditorIban": "DE89370400440532013000",
                  "creditorName": "Berlin Utility",
                  "creditorBic": "COBADEFFXXX",
                  "amount": 1234.56,
                  "currency": "EUR",
                  "remittanceInfo": "Utility bill",
                  "endToEndId": null
                }
                """.trimIndent(),
            )
            .post("/api/v1/sepa-payments")
        assertThat(created.statusCode).isEqualTo(201)
        return UUID.fromString(created.jsonPath().getString("id"))
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000008353"
        const val CREATED_EVENT = "sepa.payment.created"
        const val STATUS_CHANGED_EVENT = "sepa.payment.status-changed"
    }
}
