// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8353 — proves that `PanacheTransactionRepository.update(transaction, outboxMessage)`
 * commits the terminal `transactions` row version and its `transaction_outbox` row in **one**
 * database transaction, so a payment cannot be marked COMPLETED without the
 * `transaction.completed` event that downstream is driven by, nor the event be emitted for a
 * payment that was never completed.
 *
 * That write is the money-path terminal write moved into the workflow by #4238.
 *
 * ### Why presence is not the property
 *
 * The house pattern (`LendingOutboxWriteIT`, `ConsentRevocationOutboxIT`) drives the flow through
 * the real REST endpoint and asserts the outbox row landed. That much is necessary — a mocked
 * repository commits nothing, and a reactive Panache repo cannot be called from a bare
 * `@QuarkusTest` thread ("No current Vertx context found"), so only a real HTTP request carries the
 * Vert.x context the write needs — but it is **not sufficient**: an implementation that wrote the
 * aggregate in one transaction and the outbox row in a second would satisfy every presence
 * assertion while having lost the property outright. `TransactionApiIT` and
 * `TransactionOutboxClaimIT` are both in that position.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Rows
 * written by one transaction share an `xmin`; rows written by two cannot. Comparing them reads the
 * property directly, so splitting the write into two `Panache.withTransaction` blocks turns this
 * test red where a presence assertion stays green.
 *
 * The test carries its own control: the `transaction.initiated` row was written by the *create*
 * transaction, genuinely earlier and genuinely separate, so it must NOT match the terminal row
 * version's `xmin`. One run therefore shows the comparison both matching and not matching, which is
 * what makes a match evidence rather than a coincidence of this database.
 *
 * The scheduled outbox dispatcher is switched off here: it claims rows and marks them
 * DISPATCHING/SENT, and an UPDATE writes a new row version under a *new* `xmin`, racing the
 * assertion. (`openbank.outbox.dispatch-enabled` defaults to `false` fleet-wide — under which a
 * service dispatches nothing and reports no error; transaction-service sets it `true` in
 * `application.yaml`, which is why it has to be switched off here rather than left alone.)
 */
@QuarkusTest
@QuarkusTestResource(TransactionOutboxAtomicityIT.NoDispatchResource::class)
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class TransactionOutboxAtomicityIT {

    /**
     * Config-only resource. Every value is a **literal**: a `QuarkusTestProfile`/resource is loaded
     * in a different classloader from the test class, so a value computed here would be recomputed
     * and could hand the application one thing and an assertion another.
     */
    class NoDispatchResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun stop() = Unit
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `the terminal write commits the completed row version and its outbox row in one transaction`() {
        val transactionId = initiateTransaction()

        val writers = awaitCompletionEvent(transactionId)
        assertThat(writers.keys).contains(INITIATED_EVENT, COMPLETED_EVENT)

        val completed = writers.getValue(COMPLETED_EVENT)
        assertThat(completed.status).isEqualTo("COMPLETED")
        assertThat(completed.outboxXmin)
            .describedAs(
                "the COMPLETED transactions row version and its '%s' outbox row must carry the SAME " +
                    "Postgres xmin — different values mean two transactions wrote them, so one can " +
                    "commit without the other (transaction xmin=%s, outbox xmin=%s)",
                COMPLETED_EVENT,
                completed.transactionXmin,
                completed.outboxXmin,
            )
            .isEqualTo(completed.transactionXmin)

        // The control: the same comparison, in the same run, on a pair that genuinely was written
        // by two different transactions. Without it, the assertion above could be passing because
        // every row in this database happened to share one xmin.
        assertThat(writers.getValue(INITIATED_EVENT).outboxXmin)
            .describedAs("the create and the terminal write are different transactions")
            .isNotEqualTo(completed.transactionXmin)
    }

    /**
     * Guards the assertions above against reading their success out of a query that never returns
     * anything: an id that was never written must produce no pair at all.
     */
    @Test
    fun `the atomicity query returns nothing for a transaction that was never written`() {
        assertThat(writersOf(UUID.randomUUID())).isEmpty()
    }

    private fun initiateTransaction(): UUID {
        val response = RestAssured.given()
            .contentType("application/json")
            .body(
                """
                {
                  "idempotencyKey": "${UUID.randomUUID()}",
                  "type": "TRANSFER",
                  "sourceAccountId": "${UUID.randomUUID()}",
                  "targetAccountId": "${UUID.randomUUID()}",
                  "amount": "1234.56",
                  "currencyCode": "CZK",
                  "baseAmount": "1234.56",
                  "baseCurrencyCode": "CZK",
                  "description": "Outbox atomicity IT",
                  "valueDate": "${LocalDate.now()}",
                  "bookingDate": "${LocalDate.now()}"
                }
                """.trimIndent(),
            )
            .post("/api/v1/transactions")
        assertThat(response.statusCode).isEqualTo(201)
        return UUID.fromString(response.jsonPath().getString("id"))
    }

    /** The terminal write is performed asynchronously by the Temporal activity, so poll for it. */
    private fun awaitCompletionEvent(transactionId: UUID): Map<String, WriterPair> {
        repeat(POLL_ATTEMPTS) {
            val writers = writersOf(transactionId)
            if (writers.containsKey(COMPLETED_EVENT)) return writers
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError(
            "no '$COMPLETED_EVENT' outbox row for transaction $transactionId after " +
                "${POLL_ATTEMPTS * POLL_INTERVAL_MILLIS}ms — the terminal write never happened",
        )
    }

    private data class WriterPair(val transactionXmin: String, val outboxXmin: String, val status: String)

    /** Per event type: the transaction ids (`xmin`) that wrote the aggregate row and that outbox row. */
    private fun writersOf(transactionId: UUID): Map<String, WriterPair> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT o.event_type, t.xmin::text AS transaction_xmin, o.xmin::text AS outbox_xmin, t.status
            FROM transactions t
            JOIN transaction_outbox o ON o.aggregate_id = t.id
            WHERE t.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, transactionId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows else null }
                    .map { it.getString(1) to WriterPair(it.getString(2), it.getString(3), it.getString(4)) }
                    .toMap()
            }
        }
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000008353"
        const val INITIATED_EVENT = "openbank.transactions.transaction.initiated"
        const val COMPLETED_EVENT = "openbank.transactions.transaction.completed"
        const val POLL_ATTEMPTS = 60
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
