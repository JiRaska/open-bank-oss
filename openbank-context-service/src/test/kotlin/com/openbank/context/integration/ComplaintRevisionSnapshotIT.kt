// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.domain.ContextNamespace
import com.openbank.context.infrastructure.AssignmentAdministrationService
import com.openbank.context.infrastructure.ComplaintProjectionConsumer
import com.openbank.context.infrastructure.ContextGraphRepository
import com.openbank.context.infrastructure.DomesticPaymentProjectionConsumer
import com.openbank.context.infrastructure.PaymentBookingProjectionConsumer
import com.openbank.context.infrastructure.ProposeAssignmentRequest
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_complaint_revisions_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ComplaintRevisionSnapshotIT {
    @Inject
    lateinit var consumer: ComplaintProjectionConsumer

    @Inject
    lateinit var graph: ContextGraphRepository

    @Inject
    lateinit var payments: DomesticPaymentProjectionConsumer

    @Inject
    lateinit var bookings: PaymentBookingProjectionConsumer

    @Inject
    lateinit var assignments: AssignmentAdministrationService

    @Test
    fun `out of order complaint revisions remain immutable while current graph stays latest`() {
        val complaintId = UUID.randomUUID()
        val reference = "CMP-REVISION-${UUID.randomUUID()}"
        val account = UUID.randomUUID()
        val priorTransaction = UUID.randomUUID()
        val currentTransaction = UUID.randomUUID()
        val prior = event(complaintId, reference, 1, "complaint.received", "RECEIVED", account, priorTransaction)
        val current = event(complaintId, reference, 2, "complaint.resolved", "RESOLVED", account, currentTransaction)

        onVertx { consumer.consume(current) }
        onVertx { consumer.consume(prior) }
        onVertx { consumer.consume(prior) }
        assertThat(revisions(complaintId)).containsExactly(
            Triple(1L, "RECEIVED", priorTransaction),
            Triple(2L, "RESOLVED", currentTransaction),
        )
        assertThat(currentLabel(reference)).contains("RESOLVED")

        val priorView = onVertx {
            graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME.plusSeconds(1), 50, 50)
        }
        assertThat(requireNotNull(priorView).nodes.map { it.label }).anyMatch { it.contains("RECEIVED") }
        assertThat(priorView.edges.map { it.to }).contains("booking-transaction:$priorTransaction")
            .doesNotContain("booking-transaction:$currentTransaction")
        val currentView = onVertx {
            graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME.plusSeconds(2), 50, 50)
        }
        assertThat(requireNotNull(currentView).nodes.map { it.label }).anyMatch { it.contains("RESOLVED") }
        assertThat(currentView.edges.map { it.to }).contains("booking-transaction:$currentTransaction")
            .doesNotContain("booking-transaction:$priorTransaction")
        assertThat(
            onVertx {
                graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME, 50, 50)
            },
        ).isNull()

        val conflicting = prior.replace(priorTransaction.toString(), UUID.randomUUID().toString())
        assertThatThrownBy { onVertx { consumer.consume(conflicting) } }
            .hasStackTraceContaining("conflicting complaint revision")
        assertThat(revisions(complaintId)).hasSize(2)
    }

    @Test
    @TestSecurity(user = "complaint-history-reader", roles = ["ROLE_COMPLIANCE"])
    fun `historical API disclosure requires a live assignment and loses access on revocation`() {
        val reference = "CMP-HTTP-${UUID.randomUUID()}"
        val caseId = "case-${UUID.randomUUID()}"
        val complaintId = UUID.randomUUID()
        val account = UUID.randomUUID()
        onVertx {
            consumer.consume(
                event(complaintId, reference, 1, "complaint.received", "RECEIVED", account, UUID.randomUUID()),
            )
        }
        onVertx {
            consumer.consume(event(complaintId, reference, 2, "complaint.closed", "CLOSED", account, UUID.randomUUID()))
        }
        val proposal = onVertx {
            assignments.propose(
                ProposeAssignmentRequest(
                    "complaint-history-reader",
                    caseId,
                    "PAYMENT_COMPLAINT",
                    null,
                    Instant.now().plusSeconds(3600),
                    "complaint:$reference",
                ),
                "history-maker",
            )
        }
        val assignment = onVertx { assignments.decide(proposal.id, true, "history-checker") }
        given().header("X-Investigation-Case-Id", caseId)
            .header("X-Investigation-Purpose", "PAYMENT_COMPLAINT")
            .queryParam("asOf", TIME.plusSeconds(1).toString())
            .get("/api/v1/context/complaints/$reference").then().statusCode(200)
            .body("nodes.find { it.type == 'COMPLAINT' }.sourceVersion", equalTo(1))
            .body("nodes.find { it.type == 'COMPLAINT' }.label", equalTo("Complaint $reference · RECEIVED"))
        onVertx { assignments.revoke(requireNotNull(assignment.assignmentId), "history-admin") }
        given().header("X-Investigation-Case-Id", caseId)
            .header("X-Investigation-Purpose", "PAYMENT_COMPLAINT")
            .queryParam("asOf", TIME.plusSeconds(1).toString())
            .get("/api/v1/context/complaints/$reference").then().statusCode(403)
    }

    @Test
    fun `later payment status does not erase the payment evidence at the earlier complaint time`() {
        val reference = "CMP-PAYMENT-${UUID.randomUUID()}"
        val payment = UUID.randomUUID()
        val booking = UUID.randomUUID()
        onVertx {
            consumer.consume(
                event(UUID.randomUUID(), reference, 1, "complaint.received", "RECEIVED", UUID.randomUUID(), booking),
            )
            bookings.consumeTransaction(bookingEvent(payment, booking, 1))
            payments.consume(paymentEvent(payment, 2, "SETTLED"))
            payments.consume(paymentEvent(payment, 1, "RECEIVED"))
            payments.consume(paymentEvent(payment, 2, "SETTLED"))
        }
        val earlier = requireNotNull(
            onVertx {
                graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME.plusSeconds(1), 50, 50)
            },
        )
        assertThat(earlier.nodes.single { it.key == "transaction:$payment" }.label)
            .isEqualTo("Domestic payment · RECEIVED")
        val later = requireNotNull(
            onVertx {
                graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME.plusSeconds(2), 50, 50)
            },
        )
        assertThat(later.nodes.single { it.key == "transaction:$payment" }.label)
            .isEqualTo("Domestic payment · SETTLED")
        assertThatThrownBy { onVertx { payments.consume(paymentEvent(payment, 1, "REJECTED")) } }
            .hasStackTraceContaining("conflicting graph node revision")
    }

    @Test
    fun `eligible legacy owner baseline beats a retained foreign reference and changed node sets fail replay`() {
        val reference = "CMP-BASELINE-${UUID.randomUUID()}"
        val payment = UUID.randomUUID()
        val booking = UUID.randomUUID()
        val payload = """{"eventType":"TransactionInitiated","sourceService":"transaction-service",""" +
            """"aggregateId":"$booking","originatingPaymentId":"$payment","version":0,"occurredAt":"${TIME.plusSeconds(
                1,
            )}"}"""
        onVertx { bookings.consumeTransaction(payload) }
        scopedConnection { connection ->
            connection.prepareStatement(
                "UPDATE context_nodes SET source_system = 'domestic-payment', source_version = 7, " +
                    "display_label = 'Domestic payment · SENT_TO_CLEARING' " +
                    "WHERE bank_scope = 'openbank-cz' AND node_key = ?",
            ).use { statement ->
                statement.setString(1, "transaction:$payment")
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
            connection.commit()
        }
        onVertx {
            consumer.consume(
                event(UUID.randomUUID(), reference, 1, "complaint.received", "RECEIVED", UUID.randomUUID(), booking),
            )
        }
        val view = requireNotNull(
            onVertx {
                graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME.plusSeconds(1), 50, 50)
            },
        )
        assertThat(view.nodes.single { it.key == "transaction:$payment" }.label)
            .isEqualTo("Domestic payment · SENT_TO_CLEARING")
        assertThatThrownBy {
            onVertx { bookings.consumeTransaction(payload.replace(payment.toString(), UUID.randomUUID().toString())) }
        }.hasStackTraceContaining("conflicting graph node revision")
    }

    @Test
    fun `reverse booking delivery retains the earlier relationship evidence`() {
        val reference = "CMP-BOOKING-HISTORY-${UUID.randomUUID()}"
        val payment = UUID.randomUUID()
        val booking = UUID.randomUUID()
        fun bookingEvent(version: Long): String =
            """{"eventType":"TransactionInitiated","sourceService":"transaction-service",""" +
                """"aggregateId":"$booking","version":$version,"originatingPaymentId":"$payment",""" +
                """"occurredAt":"${TIME.plusSeconds(version)}"}"""
        onVertx {
            consumer.consume(
                event(UUID.randomUUID(), reference, 1, "complaint.received", "RECEIVED", UUID.randomUUID(), booking),
            )
            payments.consume(paymentEvent(payment, 1, "RECEIVED"))
            bookings.consumeTransaction(bookingEvent(2))
            bookings.consumeTransaction(bookingEvent(1))
        }
        val prior = requireNotNull(
            onVertx {
                graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME.plusSeconds(1), 50, 50)
            },
        )
        assertThat(prior.edges).anySatisfy { edge ->
            assertThat(edge.from).isEqualTo("transaction:$payment")
            assertThat(edge.to).isEqualTo("booking-transaction:$booking")
            assertThat(edge.relation).isEqualTo("BOOKING_REQUESTED")
            assertThat(edge.sourceVersion).isEqualTo(1)
            assertThat(edge.evidenceRef).isEqualTo("transaction:$booking:1")
            assertThat(edge.validFrom).isEqualTo(TIME.plusSeconds(1))
        }
        val latest = requireNotNull(
            onVertx {
                graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME.plusSeconds(2), 50, 50)
            },
        )
        val relationship = latest.edges.single { it.relation == "BOOKING_REQUESTED" }
        assertThat(relationship.sourceVersion).isEqualTo(2)
        assertThat(relationship.evidenceRef).isEqualTo("transaction:$booking:2")
        assertThat(relationship.validFrom).isEqualTo(TIME.plusSeconds(2))
    }

    @Test
    fun `missing authoritative booking association never treats transaction identity as payment identity`() {
        val reference = "CMP-NO-MAPPING-${UUID.randomUUID()}"
        val actualTransaction = UUID.randomUUID()
        val payment = UUID.randomUUID()
        onVertx {
            consumer.consume(
                event(
                    UUID.randomUUID(),
                    reference,
                    1,
                    "complaint.received",
                    "RECEIVED",
                    UUID.randomUUID(),
                    actualTransaction,
                ),
            )
            // A domestic ID matching the transaction ID is still not an authoritative association.
            payments.consume(paymentEvent(actualTransaction, 1, "RECEIVED"))
            payments.consume(paymentEvent(payment, 1, "RECEIVED"))
            bookings.consumeTransaction(bookingEvent(payment, actualTransaction, 2))
        }
        val prior = requireNotNull(
            onVertx {
                graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME.plusSeconds(1), 50, 50)
            },
        )
        assertThat(prior.nodes.map { it.key }).contains("booking-transaction:$actualTransaction")
            .doesNotContain("transaction:$actualTransaction", "transaction:$payment")
        assertThat(prior.edges.map { it.relation }).doesNotContain("BOOKING_REQUESTED", "CREATED")
        val later = requireNotNull(
            onVertx {
                graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME.plusSeconds(2), 50, 50)
            },
        )
        assertThat(later.nodes.map { it.key }).contains("transaction:$payment")
            .doesNotContain("transaction:$actualTransaction")
        assertThat(later.nodes.single { it.key == "booking-transaction:$actualTransaction" }.sourceSystem)
            .isEqualTo("transaction-service")
        assertThat(later.edges.single { it.relation == "BOOKING_REQUESTED" }.from).isEqualTo("transaction:$payment")
        val bounded = requireNotNull(
            onVertx {
                graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME.plusSeconds(2), 50, 2)
            },
        )
        assertThat(bounded.truncated).isTrue()
        assertThat(bounded.edges).hasSize(2)
        assertThat(bounded.nodes.map { it.key }).doesNotContain("transaction:$payment")
    }

    @Test
    fun `booking and reversal ledger facts remain visible without a payment association`() {
        val reference = "CMP-BOOKING-ONLY-${UUID.randomUUID()}"
        val transaction = UUID.randomUUID()
        val reversal = UUID.randomUUID()
        val journal = UUID.randomUUID()
        val reversalJournal = UUID.randomUUID()
        val at = TIME.plusSeconds(1)
        fun ledgerEvent(journalId: UUID, transactionId: UUID): String =
            """{"eventType":"JournalPosted","sourceService":"ledger-service","aggregateId":"$journalId",""" +
                """"version":1,"transactionId":"$transactionId","entryDate":"2026-09-01","occurredAt":"$at"}"""
        val reversalEvent =
            """{"eventType":"TransactionInitiated","sourceService":"transaction-service",""" +
                """"aggregateId":"$reversal","version":1,"type":"REVERSAL","reversalOf":"$transaction",""" +
                """"occurredAt":"$at"}"""
        onVertx {
            consumer.consume(
                event(
                    UUID.randomUUID(),
                    reference,
                    1,
                    "complaint.received",
                    "RECEIVED",
                    UUID.randomUUID(),
                    transaction,
                ),
            )
            // Domestic evidence with the same UUID must not become payment correlation.
            payments.consume(paymentEvent(transaction, 1, "RECEIVED"))
            bookings.consumeLedger(ledgerEvent(journal, transaction))
            bookings.consumeTransaction(reversalEvent)
            bookings.consumeLedger(ledgerEvent(reversalJournal, reversal))
        }
        val view = requireNotNull(
            onVertx {
                graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", at, 50, 50)
            },
        )
        assertThat(view.nodes.map { it.key }).contains(
            "booking-transaction:$transaction",
            "booking-transaction:$reversal",
            "ledger-booking:$journal",
            "ledger-booking:$reversalJournal",
        ).doesNotContain("transaction:$transaction", "payment-stage:domestic:$transaction:1")
        assertThat(view.edges.map { it.relation }).doesNotContain("BOOKING_REQUESTED", "CREATED")
        assertThat(view.edges).anySatisfy { edge ->
            assertThat(edge.from).isEqualTo("booking-transaction:$transaction")
            assertThat(edge.to).isEqualTo("booking-transaction:$reversal")
            assertThat(edge.relation).isEqualTo("REVERSED_BY")
            assertThat(edge.evidenceRef).isEqualTo("transaction:$reversal:1")
        }
        assertThat(view.edges.filter { it.relation == "BOOKED_AS" }.map { it.to })
            .containsExactlyInAnyOrder("ledger-booking:$journal", "ledger-booking:$reversalJournal")
        assertThat(view.truncated).isFalse()
    }

    private fun bookingEvent(payment: UUID, booking: UUID, revision: Long): String =
        """{"eventType":"TransactionInitiated","sourceService":"transaction-service",""" +
            """"aggregateId":"$booking","version":$revision,"originatingPaymentId":"$payment",""" +
            """"occurredAt":"${TIME.plusSeconds(revision)}"}"""

    private fun paymentEvent(id: UUID, revision: Long, status: String): String {
        val type = if (revision == 1L) "DOMESTIC_PAYMENT_CREATED" else "DOMESTIC_PAYMENT_STATUS_CHANGED"
        val statusField = if (revision == 1L) "status" else "newStatus"
        return """{"eventType":"$type","sourceService":"domestic-payment","paymentId":"$id",""" +
            """"aggregateRevision":$revision,"$statusField":"$status","occurredAt":"${TIME.plusSeconds(revision)}"}"""
    }

    private fun event(
        complaintId: UUID,
        reference: String,
        revision: Long,
        type: String,
        status: String,
        account: UUID,
        transaction: UUID,
    ): String {
        val occurredAt = TIME.plusSeconds(revision)
        return """{"schemaVersion":1,"sourceVersion":$revision,"aggregateRevision":$revision,""" +
            """"eventType":"$type","sourceService":"dispute-service","complaintId":"$complaintId",""" +
            """"reference":"$reference","status":"$status","occurredAt":"$occurredAt",""" +
            """"accountId":"$account","transactionId":"$transaction"}"""
    }

    private fun revisions(id: UUID): List<Triple<Long, String, UUID>> = scopedConnection { connection ->
        connection.prepareStatement(
            """SELECT source_version, status, transaction_id FROM context_complaint_revisions
               WHERE complaint_id = ? ORDER BY source_version""",
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            Triple(
                                rows.getLong(1),
                                rows.getString(2),
                                rows.getObject(3, UUID::class.java),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun currentLabel(reference: String): String = scopedConnection { connection ->
        connection.prepareStatement(
            "SELECT display_label FROM context_nodes WHERE bank_scope = 'openbank-cz' AND node_key = ?",
        ).use { statement ->
            statement.setString(1, "complaint:$reference")
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private fun <T> scopedConnection(action: (java.sql.Connection) -> T): T = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use { connection ->
        connection.autoCommit = false
        connection.prepareStatement("SELECT set_config('openbank.bank_scope', ?, true)").use { statement ->
            statement.setString(1, "openbank-cz")
            statement.executeQuery().close()
        }
        action(connection)
    }

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private companion object {
        val TIME: Instant = Instant.parse("2026-09-01T12:00:00Z")
    }
}
