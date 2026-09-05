// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.swift.integration

import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeSubmissionOutcome
import com.openbank.swift.application.port.out.SettlementOutcome
import com.openbank.swift.application.port.out.SettlementPort
import com.openbank.swift.domain.model.SwiftMessage
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
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8353 — proves that both of `SwiftService`'s outbox write paths commit the
 * `swift_messages` row and its `swift_outbox` row in **one** database transaction, so neither can
 * exist without the other:
 *
 *  1. `submitToScheme` — the scheme's `pacs.002` verdict lands as `SENT`/`REJECTED` together with
 *     the `swift.message.status-changed` event, and
 *  2. `settle` — the ADR-0108 booking in transaction-service advances the message to `COMPLETED`
 *     together with the second status-changed event.
 *
 * Both go through `SwiftRepository.saveWithOutbox`, whose `Panache.withTransaction` block is the
 * only transaction boundary in the flow (neither `SwiftResource` nor `SwiftService` carries
 * `@WithTransaction`), so it is also the only place a split can be introduced — and where the
 * falsification below was applied.
 *
 * ### Why presence is not the property
 *
 * The house pattern drives the flow through the real REST endpoint and asserts the outbox row
 * landed. That is necessary — a mocked repository commits nothing, and a reactive Hibernate repo
 * cannot be driven from a bare `@QuarkusTest` thread ("No current Vertx context found"), so only a
 * real HTTP request exercises the write — but it is **not sufficient**: an implementation that
 * persisted the message in one transaction and the outbox row in a second would satisfy every
 * presence assertion while having lost the property. The sibling [SwiftOutboxClaimIT] seeds outbox
 * rows directly and tests claim semantics, so it is silent about the write.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Two rows
 * written by one transaction carry the *same* `xmin`; two rows written by two transactions cannot.
 * Splitting `saveWithOutbox` into two `Panache.withTransaction` blocks turns this test red naming
 * both ids, where a presence assertion stays green.
 *
 * The scheduled outbox dispatcher is switched off for this class: it UPDATEs claimed rows, and an
 * UPDATE writes a new row version with a *new* `xmin`, which would race the assertion. swift ships
 * `openbank.outbox.dispatch-enabled: true` and — unlike interest and billing — does **not** disable
 * `quarkus.scheduler` under `%test`, so the tick really would fire here; leaving the flag alone was
 * never an option.
 *
 * That switch lives in a [QuarkusTestProfile] and NOT in a `@QuarkusTestResource`, deliberately: a
 * test resource is applied to **every** test class in the module, which is how the same change
 * turned sdd's dispatch IT red in #8676. A profile is per-class and forces this class its own
 * Quarkus boot, which is exactly the scope wanted.
 *
 * The two collaborators are stubbed the same way, through the profile's
 * [QuarkusTestProfile.getEnabledAlternatives] rather than `@io.quarkus.test.Mock`: an enabled
 * alternative is selected only under this profile, so `SchemeGatewayAdapter` and
 * `SettlementAdapter` stay in place for every other test class in the module. Without them the
 * pilot flag would be on with nothing behind it and `submitToScheme`'s catch-all would hold the
 * message in VALIDATED — the test would assert over an empty outbox while looking like it passed,
 * which is why every leg below also asserts the status the REST call actually returned.
 */
@QuarkusTest
@TestProfile(SwiftOutboxAtomicityIT.SchemeSubmissionNoDispatchProfile::class)
@QuarkusTestResource(SwiftOutboxAtomicityIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.swift.it.PostgresRedisTestResource::class)
class SwiftOutboxAtomicityIT {

    class SchemeSubmissionNoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // ADR-0104 D4's pilot flag defaults OFF; with it off submitToScheme returns before
            // either write path is reached and there is nothing to measure.
            "openbank.swift.scheme-submission.enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> =
            setOf(AcceptingSchemeGateway::class.java, AccountIdDrivenSettlement::class.java)
    }

    /** Stands in for `openbank-clearing-simulator`: every submission is accepted (`ACSC`). */
    @Alternative
    @ApplicationScoped
    class AcceptingSchemeGateway : SchemeGatewayPort {
        override suspend fun submit(message: SwiftMessage) =
            SchemeSubmissionOutcome(accepted = true, reasonCode = null, rawMt = PACS_008)

        private companion object {
            const val PACS_008 = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\"/>"
        }
    }

    /**
     * Mirrors `SettlementAdapter`'s own rule rather than inventing a switch: a message with no
     * `orderingCustomerAccountId` cannot be booked, so it stays SENT. That is what gives this test
     * one message that exercises write path 1 alone and one that exercises both.
     */
    @Alternative
    @ApplicationScoped
    class AccountIdDrivenSettlement : SettlementPort {
        override suspend fun settle(message: SwiftMessage) = SettlementOutcome(
            settled = message.orderingCustomerAccountId != null,
            transactionId = message.orderingCustomerAccountId?.let { UUID.randomUUID() },
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
    fun `the scheme-verdict write commits the message row and its outbox row in one transaction`() {
        // No orderingCustomerAccountId, so settlement is a no-op and the message ends at SENT with
        // exactly one outbox row — write path 1 in isolation.
        val messageId = send(orderingCustomerAccountId = null, expectedStatus = "SENT")

        val pairs = writersOf(messageId)
        assertThat(pairs)
            .describedAs("exactly one swift_outbox row for message %s after the scheme verdict", messageId)
            .hasSize(1)
        val verdict = pairs.single()
        assertThat(verdict.eventType).isEqualTo(STATUS_CHANGED)
        assertThat(verdict.payload).contains("\"status\":\"SENT\"")
        assertThat(verdict.outboxXmin)
            .describedAs(
                "the swift_messages row and its outbox row must carry the SAME Postgres xmin — " +
                    "different values mean two transactions wrote them, so one can commit without " +
                    "the other (message xmin=%s, outbox xmin=%s)",
                verdict.messageXmin,
                verdict.outboxXmin,
            )
            .isEqualTo(verdict.messageXmin)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `the settlement write commits the message row and its outbox row in one transaction`() {
        val messageId = send(orderingCustomerAccountId = UUID.randomUUID(), expectedStatus = "COMPLETED")

        val pairs = writersOf(messageId)
        assertThat(pairs)
            .describedAs("both status changes reached swift_outbox for message %s", messageId)
            .hasSize(2)

        val settled = pairs.single { it.payload.contains("\"status\":\"COMPLETED\"") }
        assertThat(settled.outboxXmin)
            .describedAs(
                "settle() must also write the message row and its outbox row in one transaction " +
                    "(message xmin=%s, outbox xmin=%s)",
                settled.messageXmin,
                settled.outboxXmin,
            )
            .isEqualTo(settled.messageXmin)

        // Known-different control, so one run shows the identical comparison both matching and NOT
        // matching. settle() UPDATEd the message row, giving it a new version whose xmin is the
        // settling transaction's; the SENT outbox row was written by the earlier scheme-verdict
        // transaction and therefore must NOT match the current message row. Were the comparison
        // matching everything, this would fail.
        val sent = pairs.single { it.payload.contains("\"status\":\"SENT\"") }
        assertThat(sent.outboxXmin)
            .describedAs(
                "control: the SENT outbox row was written by an earlier transaction than the " +
                    "current message row version (SENT outbox xmin=%s, message xmin=%s)",
                sent.outboxXmin,
                sent.messageXmin,
            )
            .isNotEqualTo(sent.messageXmin)
    }

    /**
     * Guards the assertions above against reading their own success from an empty set: a message id
     * that was never written must produce no pair at all, so `hasSize(1)`/`hasSize(2)` are claims
     * the query is capable of failing.
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
    private fun send(orderingCustomerAccountId: UUID?, expectedStatus: String): UUID {
        val reference = "TRX-${UUID.randomUUID().toString().take(REFERENCE_SUFFIX_LENGTH)}"
        val orderingAccountIdJson = orderingCustomerAccountId?.let { "\"$it\"" } ?: "null"
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
                  "orderingCustomerAccountId": $orderingAccountIdJson,
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
        // Arrangement assertion: submitToScheme swallows every failure and holds the message in
        // VALIDATED, so without this a broken stub (or a flag that never took effect) would leave
        // the outbox empty and the test asserting nothing.
        assertThat(body.path<String>("status"))
            .describedAs("the flow really reached the write path under test")
            .isEqualTo(expectedStatus)
        return UUID.fromString(body.path("id"))
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"

        /** `swift_messages.transaction_reference` is `varchar(16)`; `TRX-` + 12 hex fills it exactly. */
        const val REFERENCE_SUFFIX_LENGTH = 12

        /** The wire value `SwiftService` stamps on both outbox rows — the subject. */
        const val STATUS_CHANGED = "swift.message.status-changed"
    }
}
