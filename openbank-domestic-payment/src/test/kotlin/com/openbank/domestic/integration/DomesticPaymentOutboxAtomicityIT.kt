// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.domestic.it.PostgresRedisTestResource
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
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8353 — proves that `DomesticPaymentRepositoryImpl.save` commits the `domestic_payments`
 * row and its `domestic_payment_outbox` row in **one** database transaction, so neither can exist
 * without the other.
 *
 * ### Why presence is not the property
 *
 * The house pattern (`LendingOutboxWriteIT`, `ConsentRevocationOutboxIT`) drives the flow through
 * the real REST endpoint and asserts the outbox row landed. That is necessary — a mocked repository
 * commits nothing, and a reactive Panache repo cannot be called from a bare `@QuarkusTest` thread
 * ("No current Vertx context found"), so only a real HTTP request can exercise the write — but it
 * is **not sufficient**: an implementation that persisted the aggregate in one transaction and the
 * outbox row in a second would satisfy every presence assertion while having lost the property.
 * The sibling [DomesticPaymentIdempotencyIT] is in that position: it counts outbox rows, and a
 * two-transaction `save` would keep its counts at 1.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Two rows
 * written by one transaction carry the *same* `xmin`; two rows written by two transactions cannot.
 * Comparing the two `xmin`s is therefore a direct read of the property itself rather than of its
 * happy-path shadow — splitting `save` into two `Panache.withTransaction` blocks turns this test
 * red, where a presence assertion stays green.
 *
 * The scheduled outbox dispatcher is switched off for this class: it UPDATEs claimed rows, and an
 * UPDATE writes a new row version with a *new* `xmin`, which would race the assertion. (Production
 * runs with `openbank.outbox.dispatch-enabled: true` in `application.yaml`; a service that shipped
 * the `false` default would never dispatch at all, silently.)
 */
@QuarkusTest
@QuarkusTestResource(DomesticPaymentOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class DomesticPaymentOutboxAtomicityIT {

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
    fun `creating a payment commits the payment row and its outbox row in one transaction`() {
        val created = post("atomicity-it-${UUID.randomUUID()}")
        assertThat(created.statusCode).isEqualTo(201)
        val paymentId = UUID.fromString(created.jsonPath().getString("id"))

        val rows = writersOf(paymentId)

        assertThat(rows)
            .describedAs("exactly one domestic_payment_outbox row for payment %s", paymentId)
            .hasSize(1)
        val (paymentXmin, outboxXmin, eventType) = rows.single()
        assertThat(eventType).isEqualTo("domestic.payment.created")
        assertThat(outboxXmin)
            .describedAs(
                "the domestic_payments row and its outbox row must carry the SAME Postgres xmin — " +
                    "different values mean two transactions wrote them, so one can commit without " +
                    "the other (payment xmin=%s, outbox xmin=%s)",
                paymentXmin,
                outboxXmin,
            )
            .isEqualTo(paymentXmin)
    }

    /**
     * Guards the assertion above against reading its own success from an empty set: a payment id
     * that was never written must produce no pair at all, so `hasSize(1)` above is a claim the
     * query is capable of failing.
     */
    @Test
    fun `the atomicity query returns nothing for a payment that was never written`() {
        assertThat(writersOf(UUID.randomUUID())).isEmpty()
    }

    private data class WriterPair(val paymentXmin: String, val outboxXmin: String, val eventType: String)

    /** The transaction ids (`xmin`) that wrote the aggregate row and each of its outbox rows. */
    private fun writersOf(paymentId: UUID): List<WriterPair> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT p.xmin::text AS payment_xmin, o.xmin::text AS outbox_xmin, o.event_type
            FROM domestic_payments p
            JOIN domestic_payment_outbox o ON o.aggregate_id = p.payment_id
            WHERE p.payment_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, paymentId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows else null }
                    .map { WriterPair(it.getString(1), it.getString(2), it.getString(3)) }
                    .toList()
            }
        }
    }

    private fun post(key: String): Response = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", key)
        .body(
            """
            {
              "debtorAccountId": "${UUID.randomUUID()}",
              "debtorAccountNumber": "1234567890",
              "debtorBankCode": "0800",
              "debtorName": "Alice Example",
              "creditorAccountNumber": "9876543210",
              "creditorBankCode": "0100",
              "creditorName": "Brno Utility",
              "amount": ${BigDecimal("1234.56")},
              "currency": "CZK",
              "variableSymbol": "2026001",
              "specificSymbol": null,
              "constantSymbol": "0308",
              "messageForPayee": "Utility bill",
              "priority": "STANDARD",
              "transferScope": null,
              "technicalAccountCode": null,
              "statementLabel": "Monthly settlement",
              "endToEndId": "E2E-ATOMICITY-IT"
            }
            """.trimIndent(),
        )
        .post("/api/v1/domestic-payments")

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"
    }
}
