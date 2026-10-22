// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.money.Money
import com.openbank.libs.iso20022.Pacs004Builder
import com.openbank.libs.iso20022.PaymentReturn
import com.openbank.libs.iso20022.SettlementMethod
import com.openbank.sepa.application.port.out.ReversalOutcome
import com.openbank.sepa.application.port.out.ReversalPort
import com.openbank.sepa.application.port.out.ReversalUnavailableException
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.application.port.out.SepaRefundQuery
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaRejectReason
import com.openbank.sepa.domain.model.SepaReturnIntent
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.coroutines.uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
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
@TestProfile(SepaPaymentReturnAuditEvidenceIT.Profile::class)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
class SepaPaymentReturnAuditEvidenceIT {

    class Profile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(ProvenReversal::class.java)

        // Global WireMock resources enable the OIDC client at runtime; align this profile's build-time setting.
        override fun getConfigOverrides(): Map<String, String> = mapOf("quarkus.oidc-client.enabled" to "true")
    }

    @Alternative
    @ApplicationScoped
    class ProvenReversal : ReversalPort {
        @Inject
        lateinit var repository: SepaPaymentRepository

        override suspend fun reverseTransaction(
            transactionId: UUID,
            idempotencyKey: String,
            reason: String,
        ): ReversalOutcome {
            if (reason == "RACE") {
                val paymentId = UUID.fromString(idempotencyKey.removePrefix("sepa-reversal-"))
                // beginReturn has committed before the service enters this external port. Read a
                // genuinely fresh revision, then try to bypass the domain via the general writer.
                val fresh = checkNotNull(repository.findById(paymentId))
                val pending = checkNotNull(fresh.returnIntent)
                check(pending.resolvedAt == null && pending.originalTransactionId == transactionId)
                val candidates = listOf(
                    fresh.copy(status = SepaPaymentStatus.REJECTED, rejectReason = SepaRejectReason.TECHNICAL_ERROR),
                    fresh.copy(returnIntent = null),
                    fresh.copy(returnIntent = pending.copy(reasonCode = "AC01")),
                )
                for (candidate in candidates) {
                    try {
                        repository.update(
                            candidate.copy(revision = fresh.revision + 1),
                            SepaPaymentOutboxMessage(
                                paymentId,
                                "it.return-conflict-refused",
                                "{}",
                                createdAt = Instant.now(),
                            ),
                        )
                        error("fresh conflicting return write was accepted")
                    } catch (_: ResourceConflictException) {
                        // Expected application rejection, not a SQL constraint failure.
                    }
                    val unchanged = checkNotNull(repository.findById(paymentId))
                    check(unchanged.status == fresh.status && unchanged.revision == fresh.revision)
                    check(unchanged.returnIntent == pending)
                }
            }
            if (reason == "FAIL") throw ReversalUnavailableException("HTTP 409")
            return ReversalOutcome(true, UUID.fromString("00000000-0000-0000-0000-000000001363"))
        }
    }

    @Inject
    lateinit var repository: SepaPaymentRepository

    @Inject
    lateinit var refundQueries: SepaRefundQuery

    @Inject
    lateinit var objectMapper: ObjectMapper

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun seedProcessingPayment(transactionId: UUID? = UUID.randomUUID()): SepaPayment {
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
            amount = Money.of(BigDecimal("42.50"), "EUR"),
            remittanceInfo = "IT return evidence",
            endToEndId = "E2E-IT-RET-$id",
            rejectReason = null,
            rejectDetail = null,
            // A test-profile scoped reversal port proves completion without external transaction-service.
            transactionId = transactionId,
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
        assertThat(node.path("reversalPerformed").asBoolean()).isTrue()
        assertThat(node.path("reversalTransactionId").asText()).isEqualTo("00000000-0000-0000-0000-000000001363")
        assertThat(node.path("version").asLong())
            .describedAs("the evidence names the exact RETURNED aggregate revision")
            .isEqualTo(payment.revision + 2)
        // These four are what audit-service's AuditConsumer reads; getting them wrong lands the row
        // on its "unknown"/absent sentinels, and `source_service` is chain-hashed into `record_hash`
        // so attribution cannot be corrected after the fact.
        assertThat(node.path("eventType").asText()).isEqualTo("sepa.payment.returned")
        assertThat(node.path("sourceService").asText()).isEqualTo("sepa-payment")
        assertThat(node.path("occurredAt").asText()).isNotBlank()
        assertThat(Instant.parse(node.path("occurredAt").asText()))
            .describedAs("a real event time, not an Instant.EPOCH placeholder")
            .isAfter(Instant.now().minusSeconds(600))

        val row = returnRow(payment.id)
        assertThat(row["status"]).isEqualTo("RETURNED")
        assertThat(row["return_resolved_at"]).isNotNull()
        assertThat(row["return_original_transaction_id"]).isEqualTo(payment.transactionId.toString())
        // The transition itself still commits its own status-changed event — the evidence row is
        // additive, and both were written in ONE transaction with the payment row.
        assertThat(outboxPayloads(payment.id, "sepa.payment.status-changed")).hasSize(1)
    }

    private fun returnRow(paymentId: UUID): Map<String, String?> {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            val sql = "select status, completed_at, return_first_received_at, return_resolved_at, " +
                "return_unresolved_cause, return_reason_code, return_original_transaction_id, transaction_id " +
                "from sepa_payments where payment_id = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, paymentId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    listOf(
                        "status",
                        "completed_at",
                        "return_first_received_at",
                        "return_resolved_at",
                        "return_unresolved_cause",
                        "return_reason_code",
                        "return_original_transaction_id",
                        "transaction_id",
                    )
                        .associateWith { result.getString(it) }
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `missing transaction remains durable unresolved across repeated HTTP returns`() {
        val payment = seedProcessingPayment(transactionId = null)
        fun send(reason: String) {
            Given {
                contentType(MediaType.APPLICATION_XML)
                body(pacs004(payment.endToEndId, reason))
            } When {
                post("/api/v1/sepa-payments/returns")
            } Then { statusCode(503) }
        }
        send("AM09")
        val first = returnRow(payment.id)
        send("AC01")
        val retried = returnRow(payment.id)
        assertThat(retried["status"]).isEqualTo("PROCESSING")
        assertThat(retried["completed_at"]).isNull()
        assertThat(retried["return_resolved_at"]).isNull()
        assertThat(retried["return_original_transaction_id"]).isNull()
        assertThat(retried["return_unresolved_cause"]).isEqualTo("UNRESOLVED_ORIGINAL_TRANSACTION")
        assertThat(retried["return_reason_code"]).isEqualTo("AM09")
        assertThat(retried["return_first_received_at"]).isNotNull().isEqualTo(first["return_first_received_at"])
        assertThat(outboxPayloads(payment.id, "sepa.payment.returned")).isEmpty()
        assertThat(outboxPayloads(payment.id, "sepa.payment.status-changed")).isEmpty()
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `conflict keeps original identity and never commits returned evidence`() {
        val payment = seedProcessingPayment()
        Given {
            contentType(MediaType.APPLICATION_XML)
            body(pacs004(payment.endToEndId, "FAIL"))
        } When {
            post("/api/v1/sepa-payments/returns")
        } Then { statusCode(503) }
        val row = returnRow(payment.id)
        assertThat(row["status"]).isEqualTo("PROCESSING")
        assertThat(row["return_original_transaction_id"]).isEqualTo(payment.transactionId.toString())
        assertThat(row["return_unresolved_cause"]).isEqualTo("REVERSAL_UNPROVEN")
        assertThat(row["return_resolved_at"]).isNull()
        assertThat(outboxPayloads(payment.id, "sepa.payment.returned")).isEmpty()
    }

    private fun executeJdbc(sql: String) {
        val cfg = ConfigProvider.getConfig()
        DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `failed evidence insert rolls back returned and resolved marker together`() {
        val payment = seedProcessingPayment()
        val constraint = "return_evidence_${payment.id.toString().replace("-", "")}"
        // Restrict the injected DB failure to this synthetic aggregate; other test rows are untouched.
        executeJdbc(
            "ALTER TABLE sepa_payment_outbox ADD CONSTRAINT $constraint " +
                "CHECK (aggregate_id <> '${payment.id}' OR event_type <> 'sepa.payment.returned')",
        )
        try {
            Given {
                contentType(MediaType.APPLICATION_XML)
                body(pacs004(payment.endToEndId, "AM09"))
            } When {
                post("/api/v1/sepa-payments/returns")
            } Then { statusCode(409) }
            val pending = returnRow(payment.id)
            assertThat(pending["status"]).isEqualTo("PROCESSING")
            assertThat(pending["return_first_received_at"]).isNotNull()
            assertThat(pending["return_resolved_at"]).isNull()
            assertThat(outboxPayloads(payment.id, "sepa.payment.returned")).isEmpty()
            assertThat(outboxPayloads(payment.id, "sepa.payment.status-changed")).isEmpty()
        } finally {
            executeJdbc("ALTER TABLE sepa_payment_outbox DROP CONSTRAINT $constraint")
        }
        // A proven retry can finish the still-pending intent after the injected failure is removed.
        Given {
            contentType(MediaType.APPLICATION_XML)
            body(pacs004(payment.endToEndId, "AM09"))
        } When {
            post("/api/v1/sepa-payments/returns")
        } Then { statusCode(200) }
        assertThat(returnRow(payment.id)["return_resolved_at"]).isNotNull()
        assertThat(outboxPayloads(payment.id, "sepa.payment.returned")).hasSize(1)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `fresh conflicting writer during reversal cannot strand intent and replay emits no second evidence`() {
        val payment = seedProcessingPayment()
        // The profile's reversal port deterministically interleaves fresh general repository writes
        // AFTER the intent commit but BEFORE returning a proven reversal to the original request.
        Given {
            contentType(MediaType.APPLICATION_XML)
            body(pacs004(payment.endToEndId, "RACE"))
        } When {
            post("/api/v1/sepa-payments/returns")
        } Then { statusCode(200) }
        val completed = returnRow(payment.id)
        assertThat(completed["status"]).isEqualTo("RETURNED")
        assertThat(completed["return_resolved_at"]).isNotNull()
        assertThat(completed["return_reason_code"]).isEqualTo("RACE")
        assertThat(outboxPayloads(payment.id, "it.return-conflict-refused")).isEmpty()
        assertThat(outboxPayloads(payment.id, "sepa.payment.returned")).hasSize(1)
        assertThat(outboxPayloads(payment.id, "sepa.payment.status-changed")).hasSize(1)
        Given {
            contentType(MediaType.APPLICATION_XML)
            body(pacs004(payment.endToEndId, "AC01"))
        } When {
            post("/api/v1/sepa-payments/returns")
        } Then { statusCode(200) }
        assertThat(returnRow(payment.id)).isEqualTo(completed)
        assertThat(outboxPayloads(payment.id, "sepa.payment.returned")).hasSize(1)
        assertThat(outboxPayloads(payment.id, "sepa.payment.status-changed")).hasSize(1)
    }

    private fun completeKnownSettlement(snapshot: SepaPayment, transactionId: UUID): SepaPayment = onEventLoop {
        // In production only the workflow calls this after SettlementResponseCheck has proved
        // the same snapshot's request. No lookup or new financial request is made by this port.
        repository.completeSettlement(snapshot, transactionId, Instant.now()) { current ->
            SepaPaymentOutboxMessage(
                current.id,
                "sepa.payment.status-changed",
                objectMapper.writeValueAsString(
                    mapOf(
                        "paymentId" to current.id,
                        "version" to current.revision,
                        "status" to "COMPLETED",
                        "occurredAt" to current.updatedAt.toString(),
                        "sourceService" to "sepa-payment",
                    ),
                ),
                createdAt = current.updatedAt,
            )
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `matched in-flight settlement attaches first identity without losing pending return and retry resolves once`() {
        val snapshotBeforeNetwork = seedProcessingPayment(transactionId = null)
        val bookedId = UUID.randomUUID()
        Given {
            contentType(MediaType.APPLICATION_XML)
            header("X-Correlation-ID", "original-before-settlement")
            body(pacs004(snapshotBeforeNetwork.endToEndId, "AM09"))
        } When { post("/api/v1/sepa-payments/returns") } Then { statusCode(503) }
        val pending = returnRow(snapshotBeforeNetwork.id)
        assertThat(pending["return_original_transaction_id"]).isNull()
        fun complete(transactionId: UUID = bookedId): SepaPayment =
            completeKnownSettlement(snapshotBeforeNetwork, transactionId)
        // A failed completion outbox write cannot leave a detached identity or changed marker.
        val constraint = "settlement_evidence_${snapshotBeforeNetwork.id.toString().replace("-", "")}"
        executeJdbc(
            "ALTER TABLE sepa_payment_outbox ADD CONSTRAINT $constraint " +
                "CHECK (aggregate_id <> '${snapshotBeforeNetwork.id}' OR event_type <> 'sepa.payment.status-changed')",
        )
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy { complete() }.isInstanceOf(Exception::class.java)
            assertThat(returnRow(snapshotBeforeNetwork.id)).isEqualTo(pending)
            assertThat(outboxPayloads(snapshotBeforeNetwork.id, "sepa.payment.status-changed")).isEmpty()
        } finally {
            executeJdbc("ALTER TABLE sepa_payment_outbox DROP CONSTRAINT $constraint")
        }
        val completed = complete()
        assertThat(completed.transactionId).isEqualTo(bookedId)
        assertThat(completed.returnIntent?.originalTransactionId).isEqualTo(bookedId)
        assertThat(completed.returnIntent?.idempotencyKey(completed.id)).isEqualTo("sepa-reversal-${completed.id}")
        assertAttachedSettlement(completed, bookedId, pending)
        Given {
            contentType(MediaType.APPLICATION_XML)
            body(pacs004(completed.endToEndId, "AC01"))
        } When {
            post("/api/v1/sepa-payments/returns")
        } Then { statusCode(200) }
        val returned = returnRow(completed.id)
        assertThat(returned["status"]).isEqualTo("RETURNED")
        assertThat(returned["return_first_received_at"]).isEqualTo(pending["return_first_received_at"])
        assertThat(returned["return_reason_code"]).isEqualTo("AM09")
        val evidence = objectMapper.readTree(outboxPayloads(completed.id, "sepa.payment.returned").single())
        assertThat(evidence.path("correlationId").asText()).isEqualTo("original-before-settlement")
        assertThat(complete().status).isEqualTo(SepaPaymentStatus.RETURNED)
        org.assertj.core.api.Assertions.assertThatThrownBy { complete(UUID.randomUUID()) }
            .satisfies(
                org.assertj.core.api.ThrowingConsumer<Throwable> { failure ->
                    assertThat(generateSequence(failure) { it.cause }.any { it is ResourceConflictException }).isTrue()
                },
            )
        Given {
            contentType(MediaType.APPLICATION_XML)
            body(pacs004(completed.endToEndId, "AM09"))
        } When {
            post("/api/v1/sepa-payments/returns")
        } Then { statusCode(200) }
        assertThat(returnRow(completed.id)).isEqualTo(returned)
        assertThat(outboxPayloads(completed.id, "sepa.payment.returned")).hasSize(1)
        assertThat(outboxPayloads(completed.id, "sepa.payment.status-changed")).hasSize(2)
    }

    private fun assertAttachedSettlement(completed: SepaPayment, bookedId: UUID, pending: Map<String, String?>) {
        val attached = returnRow(completed.id)
        assertThat(attached["status"]).isEqualTo("COMPLETED")
        assertThat(attached["return_first_received_at"]).isEqualTo(pending["return_first_received_at"])
        assertThat(attached["return_reason_code"]).isEqualTo(pending["return_reason_code"])
        assertThat(attached["return_original_transaction_id"]).isEqualTo(bookedId.toString())
        assertThat(attached["transaction_id"]).isEqualTo(bookedId.toString())
        assertThat(attached["return_resolved_at"]).isNull()
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `oldest unresolved return query uses receipt rather than creation and excludes resolved refunds`() {
        fun seedReceipt(received: Instant): SepaPayment {
            val payment = seedProcessingPayment()
            return onEventLoop {
                repository.beginReturn(
                    payment.id,
                    SepaReturnIntent(payment.transactionId, received, "AM09", "actor", "ROLE_API", null),
                )
            }
        }
        fun resolve(payment: SepaPayment) {
            Given {
                contentType(MediaType.APPLICATION_XML)
                body(pacs004(payment.endToEndId, "AM09"))
            } When {
                post("/api/v1/sepa-payments/returns")
            } Then { statusCode(200) }
        }
        // All aggregates are created now, but durable refund receipts have their own clock.
        // Old fixture dates also separate these rows from other methods' current-time pending rows.
        val resolvedOlder = seedReceipt(Instant.parse("2000-01-01T00:00:00Z"))
        val laterPending = seedReceipt(Instant.parse("2002-01-01T00:00:00Z"))
        val earlierPending = seedReceipt(Instant.parse("2001-01-01T00:00:00Z"))
        resolve(resolvedOlder)
        assertThat(onEventLoop { refundQueries.oldestUnresolvedReturnAt() })
            .isEqualTo(earlierPending.returnIntent?.firstReceivedAt)
        assertThat(returnRow(resolvedOlder.id)["return_resolved_at"]).isNotNull()
        resolve(earlierPending)
        assertThat(onEventLoop { refundQueries.oldestUnresolvedReturnAt() })
            .isEqualTo(laterPending.returnIntent?.firstReceivedAt)
        resolve(laterPending)
    }
}
