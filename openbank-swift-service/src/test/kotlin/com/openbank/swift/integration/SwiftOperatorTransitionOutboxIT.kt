// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.swift.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8718 — the two OPERATOR-driven transitions must publish, and must publish atomically.
 *
 * `SwiftService` announced every SCHEME decision (`submitToScheme`, `settle`) and no operator one:
 * `acknowledge` and `reject` changed `swift_messages.status` through the plain `save` and wrote no
 * outbox row at all. `audit-service` subscribes to `openbank.payments.swift.event`, so the audit
 * trail held what the counterparty decided and nothing about what a human here decided — the wrong
 * asymmetry for a money-path service, since a scheme verdict is reproducible from the
 * counterparty's own records and an operator's rejection exists only here.
 *
 * ### Why the presence assertion comes first
 *
 * The absent event is not an atomicity defect: with no second row there is nothing to diverge
 * from the first, so every "the outbox row and the aggregate share a transaction" assertion is
 * *vacuously* satisfiable by a method that writes no row. This class therefore asserts BOTH
 * halves in order — the row exists at all, and it was written by the same transaction — and the
 * first assertion is the one the old code failed.
 *
 * ### The oracle
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Two rows
 * written by one transaction carry the same `xmin`; two rows written by two transactions cannot.
 * Row presence alone cannot tell those apart, which is why the assertion is on the transaction id
 * and not on `count(*)`. The reject case additionally carries a known-DIFFERENT control, so one
 * run shows the identical comparison both matching and not matching — an equality assertion
 * that never sees an unequal pair is not known to be able to fail.
 *
 * The scheduled outbox dispatcher is switched OFF for this class: it UPDATEs claimed rows, and an
 * UPDATE writes a new row version under a new `xmin`, which would race and destroy the evidence.
 * swift ships `openbank.outbox.dispatch-enabled: true` and does not disable `quarkus.scheduler`
 * under `%test`, so the tick really would fire. The switch lives in a [QuarkusTestProfile] and not
 * in a `@QuarkusTestResource` deliberately — a test resource applies to every test class in the
 * module, a profile is per-class.
 *
 * The pilot flag `openbank.swift.scheme-submission.enabled` is left at its default OFF, which is
 * what makes the arithmetic below exact: `POST /api/v1/swift` then returns a VALIDATED message
 * having written NO outbox row, so every row this test finds was written by the operator
 * transition under test.
 *
 * The flow is driven over real HTTP (RestAssured + `@TestSecurity`) because the reactive Panache
 * repositories cannot be called from a bare `@QuarkusTest` thread ("No current Vertx context
 * found") — and because only a real request exercises the production wiring.
 */
@QuarkusTest
@TestProfile(SwiftOperatorTransitionOutboxIT.NoDispatchProfile::class)
@QuarkusTestResource(SwiftOperatorTransitionOutboxIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.swift.it.PostgresRedisTestResource::class)
class SwiftOperatorTransitionOutboxIT {

    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("swift-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `acknowledge publishes the status change in the same transaction as the state change`() {
        val messageId = send()

        // The defect's own shape, asserted before the fix's shape: with the pilot flag off, send
        // writes no outbox row, so an empty set here is the state acknowledge inherits.
        assertThat(writersOf(messageId))
            .describedAs("send with the scheme-submission pilot off must write no outbox row")
            .isEmpty()

        acknowledge(messageId)

        val rows = writersOf(messageId)
        assertThat(rows)
            .describedAs(
                "POST /api/v1/swift/%s/ack must write exactly one swift_outbox row — before #8718 " +
                    "it wrote none, so nothing downstream (audit-service included) ever learned an " +
                    "operator had acknowledged",
                messageId,
            )
            .hasSize(1)
        val ack = rows.single()
        assertThat(ack.eventType).isEqualTo(STATUS_CHANGED)
        assertThat(ack.payload).contains("\"status\":\"ACKNOWLEDGED\"")
        assertThat(ack.outboxXmin)
            .describedAs(
                "the swift_messages row and its outbox row must carry the SAME Postgres xmin — " +
                    "different values mean two transactions wrote them, so one can commit without " +
                    "the other (message xmin=%s, outbox xmin=%s)",
                ack.messageXmin,
                ack.outboxXmin,
            )
            .isEqualTo(ack.messageXmin)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `reject publishes the status change in the same transaction as the state change`() {
        val messageId = send()
        acknowledge(messageId)
        reject(messageId)

        val rows = writersOf(messageId)
        assertThat(rows)
            .describedAs("both operator transitions of message %s reached swift_outbox", messageId)
            .hasSize(2)

        val rejected = rows.single { it.payload.contains("\"status\":\"REJECTED\"") }
        assertThat(rejected.eventType).isEqualTo(STATUS_CHANGED)
        assertThat(rejected.outboxXmin)
            .describedAs(
                "reject() must write the message row and its outbox row in one transaction " +
                    "(message xmin=%s, outbox xmin=%s)",
                rejected.messageXmin,
                rejected.outboxXmin,
            )
            .isEqualTo(rejected.messageXmin)

        // Known-different control. reject() UPDATEd the message row, so the version now on disk
        // carries the REJECTING transaction's xmin; the ACKNOWLEDGED outbox row was written by the
        // earlier acknowledge() transaction and therefore must NOT match it. Were the comparison
        // above matching everything (or were both reads returning the same constant), this fails.
        val acknowledged = rows.single { it.payload.contains("\"status\":\"ACKNOWLEDGED\"") }
        assertThat(acknowledged.outboxXmin)
            .describedAs(
                "control: the ACKNOWLEDGED outbox row was written by an earlier transaction than " +
                    "the current message row version (ack outbox xmin=%s, message xmin=%s)",
                acknowledged.outboxXmin,
                acknowledged.messageXmin,
            )
            .isNotEqualTo(acknowledged.messageXmin)
    }

    /**
     * Guards the two assertions above against reading their own success out of an empty set: a
     * message id that was never written must produce no pair at all, so `hasSize(1)`/`hasSize(2)`
     * are claims the query is capable of failing.
     */
    @Test
    fun `the atomicity query returns nothing for a message that was never written`() {
        assertThat(writersOf(UUID.randomUUID())).isEmpty()
    }

    private data class WriterPair(
        val messageXmin: String,
        val outboxXmin: String,
        val eventType: String,
        val payload: String,
    )

    /** The transaction ids (`xmin`) that wrote the aggregate row and each of its outbox rows. */
    private fun writersOf(messageId: UUID): List<WriterPair> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT m.xmin::text AS message_xmin, o.xmin::text AS outbox_xmin, o.event_type, o.payload
            FROM swift_messages m
            JOIN swift_outbox o ON o.aggregate_id = m.id
            WHERE m.id = ?
            ORDER BY o.id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, messageId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows else null }
                    .map { WriterPair(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
                    .toList()
            }
        }
    }

    /** Drives the real `POST /api/v1/swift` and returns the persisted message id. */
    private fun send(): UUID {
        val reference = "TRX-${UUID.randomUUID().toString().take(REFERENCE_SUFFIX_LENGTH)}"
        val response = Given {
            contentType("application/json")
            body(
                """
                {
                  "idempotencyKey": "${UUID.randomUUID()}",
                  "messageType": "MT103",
                  "senderBic": "OPBKCZPP",
                  "receiverBic": "DEUTDEFF",
                  "transactionReference": "$reference",
                  "relatedReference": null,
                  "valueDate": "20260120",
                  "currency": "EUR",
                  "amountMinorUnits": 125000,
                  "orderingCustomerAccount": "DE89370400440532013000",
                  "orderingCustomerAccountId": null,
                  "orderingCustomerName": "Alice",
                  "beneficiaryAccount": "GB33BUKB20201555555555",
                  "beneficiaryName": "Bob",
                  "remittanceInfo": "Invoice 1",
                  "chargeCode": "SHA",
                  "priority": "NORMAL"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/swift")
        } Then {
            statusCode(201)
        }
        val body = response.extract()
        // Arrangement assertion: with the pilot flag off the message must stop at VALIDATED. Were
        // it to advance, the outbox counts below would be measuring the scheme path instead.
        assertThat(body.path<String>("status"))
            .describedAs("the scheme-submission pilot must stay off, leaving the outbox empty")
            .isEqualTo("VALIDATED")
        return UUID.fromString(body.path("id"))
    }

    private fun acknowledge(messageId: UUID) {
        val status = (
            Given {
                contentType("application/json")
                body("""{"ackRef":"ACK-8718"}""")
            } When {
                post("/api/v1/swift/$messageId/ack")
            } Then {
                statusCode(200)
            }
            ).extract().path<String>("status")
        assertThat(status)
            .describedAs("the acknowledge flow really ran")
            .isEqualTo("ACKNOWLEDGED")
    }

    private fun reject(messageId: UUID) {
        val status = (
            Given {
                contentType("application/json")
                body("""{"reason":"beneficiary details unusable"}""")
            } When {
                post("/api/v1/swift/$messageId/reject")
            } Then {
                statusCode(200)
            }
            ).extract().path<String>("status")
        assertThat(status)
            .describedAs("the reject flow really ran")
            .isEqualTo("REJECTED")
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"

        /** `swift_messages.transaction_reference` is `varchar(16)`; `TRX-` + 12 hex fills it exactly. */
        const val REFERENCE_SUFFIX_LENGTH = 12

        /** The wire value `SwiftService` stamps on every status-changed outbox row — the subject. */
        const val STATUS_CHANGED = "swift.message.status-changed"
    }
}
