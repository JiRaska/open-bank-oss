// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.infrastructure.AssignmentAdministrationService
import com.openbank.context.infrastructure.ComplaintProjectionConsumer
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
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_complaint_revisions_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ComplaintBookingContractIT {
    @Inject
    lateinit var complaints: ComplaintProjectionConsumer

    @Inject
    lateinit var payments: DomesticPaymentProjectionConsumer

    @Inject
    lateinit var bookings: PaymentBookingProjectionConsumer

    @Inject
    lateinit var assignments: AssignmentAdministrationService

    @Test
    @TestSecurity(user = "booking-contract-reader", roles = ["ROLE_COMPLIANCE"])
    fun `HTTP complaint contract preserves actual transaction and domestic payment identities`() {
        val reference = "CMP-BOOKING-CONTRACT-${UUID.randomUUID()}"
        val complaint = UUID.randomUUID()
        val transaction = UUID.randomUUID()
        val payment = UUID.randomUUID()
        val caseId = "case-${UUID.randomUUID()}"
        val at = Instant.parse("2026-09-01T12:00:00Z")
        onVertx {
            complaints.consume(
                """{"schemaVersion":1,"sourceVersion":1,"aggregateRevision":1,""" +
                    """"eventType":"complaint.received","sourceService":"dispute-service",""" +
                    """"complaintId":"$complaint","reference":"$reference","status":"RECEIVED",""" +
                    """"occurredAt":"$at","transactionId":"$transaction"}""",
            )
            payments.consume(
                """{"eventType":"DOMESTIC_PAYMENT_CREATED","sourceService":"domestic-payment",""" +
                    """"paymentId":"$payment","aggregateRevision":1,"status":"RECEIVED","occurredAt":"$at"}""",
            )
            bookings.consumeTransaction(
                """{"eventType":"TransactionInitiated","sourceService":"transaction-service",""" +
                    """"aggregateId":"$transaction","version":1,"originatingPaymentId":"$payment","occurredAt":"$at"}""",
            )
            val proposal = assignments.propose(
                ProposeAssignmentRequest(
                    "booking-contract-reader",
                    caseId,
                    "PAYMENT_COMPLAINT",
                    null,
                    Instant.now().plusSeconds(3600),
                    "complaint:$reference",
                ),
                "booking-contract-maker",
            )
            assignments.decide(proposal.id, true, "booking-contract-checker")
        }
        val concernEdge = "edges.find { it.relation == 'CONCERNS_TRANSACTION' }"
        val bookingEdge = "edges.find { it.relation == 'BOOKING_REQUESTED' }"
        val transactionNode = "nodes.find { it.key == 'booking-transaction:$transaction' }"
        val paymentNode = "nodes.find { it.key == 'transaction:$payment' }"
        given().header("X-Investigation-Case-Id", caseId)
            .header("X-Investigation-Purpose", "PAYMENT_COMPLAINT")
            .queryParam("asOf", at.toString())
            .get("/api/v1/context/complaints/$reference").then().statusCode(200)
            .body("$concernEdge.to", equalTo("booking-transaction:$transaction"))
            .body("$bookingEdge.from", equalTo("transaction:$payment"))
            .body("$bookingEdge.to", equalTo("booking-transaction:$transaction"))
            .body("$transactionNode.sourceRef", equalTo(transaction.toString()))
            .body("$transactionNode.sourceSystem", equalTo("transaction-service"))
            .body("$paymentNode.sourceRef", equalTo(payment.toString()))
            .body("$paymentNode.sourceSystem", equalTo("domestic-payment"))
    }

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
