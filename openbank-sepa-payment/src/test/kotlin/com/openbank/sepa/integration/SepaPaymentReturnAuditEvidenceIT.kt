// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.iso20022.Pacs004Builder
import com.openbank.libs.iso20022.PaymentReturn
import com.openbank.libs.iso20022.SettlementMethod
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Issue #6056 — proves that `POST /api/v1/sepa-payments/returns` leaves a durable non-repudiation
 * record, attributed to the AUTHENTICATED caller.
 *
 * **Why this test and not a unit test.** The threat model's R row credited a control that did not
 * exist, and the reason nobody noticed is that every existing test of the return path mocks the
 * repository — a mocked repository can only ever confirm that a method was called, never that a
 * row exists. So this drives the real HTTP endpoint (RestAssured + `@TestSecurity`, which is also
 * the only way the `SecurityContext` the actor is derived from is populated at all) against a real
 * Postgres, and reads the row back over **plain JDBC** — a second, independent connection that
 * shares no code with the write path. Reading it back through the service's own reactive
 * repository would ask the code under test whether it did its job.
 *
 * (The reactive Panache repository cannot be called from a bare `@QuarkusTest` thread at all —
 * `No current Vertx context found` — hence `onEventLoop` for the seeding step, the pattern
 * `SepaPaymentOutboxClaimIT` and `PaymentConfirmationSimulatorIT` already use.)
 *
 * What the evidence row is: an outbox row on `openbank.sepa.payment.events`, a topic
 * openbank-audit-service already consumes into the append-only, hash-chained `audit_entries`
 * table. This test proves the producing half — the row is written, in the same transaction as the
 * transition, with the right fields. The consuming half is audit-service's own suite.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
class SepaPaymentReturnAuditEvidenceIT {

    @Inject
    lateinit var repository: SepaPaymentRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun seedProcessingPayment(): SepaPayment {
        val id = UUID.randomUUID()
        val toSave = SepaPayment(
            id = id,
            idempotencyKey = "it-return-$id",
            type = SepaPaymentType.SCT,
            status = SepaPaymentStatus.PROCESSING,
            debtorAccountId = UUID.randomUUID(),
            debtorIban = "DE89370400440532013000",
            debtorName = "Alice Debtor",
            creditorIban = "FR1420041010050500013M02606",
            creditorName = "Bob Creditor",
            creditorBic = "BNPAFRPPXXX",
            amount = BigDecimal("42.50"),
            currency = "EUR",
            remittanceInfo = "IT return evidence",
            endToEndId = "E2E-IT-RET-$id",
            rejectReason = null,
            rejectDetail = null,
            // Deliberately no transactionId: the ledger reversal is then skipped, so this test needs
            // no transaction-service stub AND it pins the honest `reversalPerformed = false` case.
            submittedAt = Instant.now(),
            completedAt = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        return onEventLoop {
            repository.save(
                payment = toSave,
                outboxMessage = SepaPaymentOutboxMessage(
                    aggregateId = toSave.id,
                    eventType = "sepa.payment.created",
                    payload = "{}",
                    createdAt = Instant.now(),
                ),
            )
        }
    }

    private fun pacs004(endToEndId: String, reasonCode: String): String = Pacs004Builder().build(
        PaymentReturn(
            messageId = "MSG-IT-6056",
            creationDateTime = OffsetDateTime.now(),
            settlementMethod = SettlementMethod.CLRG,
            returnId = "RTR-IT-6056",
            originalEndToEndId = endToEndId,
            originalTransactionId = "TX-IT-6056",
            returnedAmount = BigDecimal("42.50"),
            currency = "EUR",
            returnReasonCode = reasonCode,
        ),
    )

    /** A second, independent connection — nothing here shares code with the write path. */
    private fun outboxPayloads(aggregateId: UUID, eventType: String): List<String> {
        val cfg = ConfigProvider.getConfig()
        val url = cfg.getValue("quarkus.datasource.jdbc.url", String::class.java)
        val user = cfg.getValue("quarkus.datasource.username", String::class.java)
        val password = cfg.getValue("quarkus.datasource.password", String::class.java)
        val payloads = mutableListOf<String>()
        DriverManager.getConnection(url, user, password).use { conn ->
            val sql = "select payload from sepa_payment_outbox where aggregate_id = ? and event_type = ?"
            conn.prepareStatement(sql).use { st ->
                st.setObject(1, aggregateId)
                st.setString(2, eventType)
                val rs = st.executeQuery()
                while (rs.next()) payloads.add(rs.getString("payload"))
            }
        }
        return payloads
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `a return leaves a durable evidence row naming the authenticated caller`() {
        val payment = seedProcessingPayment()

        Given {
            contentType(MediaType.APPLICATION_XML)
            header("X-Correlation-ID", "corr-it-6056")
            body(pacs004(payment.endToEndId, "AM09"))
        } When {
            post("/api/v1/sepa-payments/returns")
        } Then {
            statusCode(200)
        }

        val evidence = outboxPayloads(payment.id, "sepa.payment.returned")
        assertThat(evidence)
            .describedAs("exactly one non-repudiation record for one return invocation")
            .hasSize(1)

        val node = objectMapper.readTree(evidence.single())
        // The actor is the AUTHENTICATED principal. The pacs.004 body carries no actor field and
        // none is read from it — a repudiation record the disputing party fills in records nothing.
        assertThat(node.path("actorId").asText()).isEqualTo("service-account-openbank-services")
        assertThat(node.path("actorType").asText()).isEqualTo("ROLE_API")
        assertThat(node.path("correlationId").asText()).isEqualTo("corr-it-6056")
        assertThat(node.path("originalEndToEndId").asText()).isEqualTo(payment.endToEndId)
        assertThat(node.path("returnReasonCode").asText()).isEqualTo("AM09")
        assertThat(node.path("paymentId").asText()).isEqualTo(payment.id.toString())
        assertThat(node.path("reversalPerformed").asBoolean()).isFalse()
        // These four are what audit-service's AuditConsumer reads; getting them wrong lands the row
        // on its "unknown"/absent sentinels, and `source_service` is chain-hashed into `record_hash`
        // so attribution cannot be corrected after the fact.
        assertThat(node.path("eventType").asText()).isEqualTo("sepa.payment.returned")
        assertThat(node.path("sourceService").asText()).isEqualTo("sepa-payment")
        assertThat(node.path("occurredAt").asText()).isNotBlank()
        assertThat(Instant.parse(node.path("occurredAt").asText()))
            .describedAs("a real event time, not an Instant.EPOCH placeholder")
            .isAfter(Instant.now().minusSeconds(600))

        // The transition itself still commits its own status-changed event — the evidence row is
        // additive, and both were written in ONE transaction with the payment row.
        assertThat(outboxPayloads(payment.id, "sepa.payment.status-changed")).hasSize(1)
    }
}
