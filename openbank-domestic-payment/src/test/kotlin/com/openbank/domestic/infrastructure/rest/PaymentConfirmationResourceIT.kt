// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.domestic.infrastructure.client.FakePaymentConfirmationRenderPort
import com.openbank.domestic.integration.DomesticPaymentBootSmokeIT
import com.openbank.domestic.it.PostgresRedisTestResource
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Integration coverage for `GET /api/v1/domestic-payments/{paymentId}/confirmation` (ADR-0248 #3)
 * — the customer-facing "download confirmation" action. [FakePaymentConfirmationRenderPort] (an
 * `@Alternative @Priority(1)` CDI override) replaces the real document-service call, so this
 * exercises the REST layer + use case + repository against a real Postgres, without a running
 * document-service.
 */
@QuarkusTest
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class PaymentConfirmationResourceIT {

    @Inject
    lateinit var repository: DomesticPaymentRepository

    @Inject
    lateinit var fakeRenderPort: FakePaymentConfirmationRenderPort

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @BeforeEach
    fun setUp() {
        fakeRenderPort.renderedHtml = "<html>fake-confirmation</html>"
    }

    private fun newReceivedPayment(clock: Clock): DomesticPayment {
        val now = Instant.now(clock)
        return DomesticPayment(
            id = UUID.randomUUID(),
            idempotencyKey = "confirmation-it-${UUID.randomUUID()}",
            status = DomesticPaymentStatus.RECEIVED,
            debtorAccountId = UUID.randomUUID(),
            debtorAccountNumber = "1234567890",
            debtorBankCode = "0800",
            debtorName = "Debtor",
            creditorAccountNumber = "0987654321",
            creditorBankCode = "2010",
            creditorName = "Creditor Name",
            amount = BigDecimal("42.50"),
            currency = "CZK",
            variableSymbol = "999888",
            specificSymbol = null,
            constantSymbol = null,
            messageForPayee = null,
            priority = DomesticPaymentPriority.STANDARD,
            transferScope = DomesticTransferScope.EXTERNAL,
            technicalAccountCode = null,
            statementLabel = null,
            endToEndId = "DOMS${clock.millis()}",
            rejectReason = null,
            rejectDetail = null,
            submittedAt = null,
            settledAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun pathTo(status: DomesticPaymentStatus): List<DomesticPaymentStatus> = when (status) {
        DomesticPaymentStatus.RECEIVED -> emptyList()
        DomesticPaymentStatus.VALIDATED -> listOf(DomesticPaymentStatus.VALIDATED)
        DomesticPaymentStatus.SENT_TO_CLEARING -> listOf(
            DomesticPaymentStatus.VALIDATED,
            DomesticPaymentStatus.SENT_TO_CLEARING,
        )
        DomesticPaymentStatus.SETTLED -> listOf(
            DomesticPaymentStatus.VALIDATED,
            DomesticPaymentStatus.SENT_TO_CLEARING,
            DomesticPaymentStatus.SETTLED,
        )
        else -> error("Unsupported seed status in this test: $status")
    }

    private fun seedPayment(status: DomesticPaymentStatus): DomesticPayment {
        val clock = Clock.systemUTC()
        val received = newReceivedPayment(clock)

        var payment = onEventLoop {
            repository.save(
                received,
                OutboxMessage(aggregateId = received.id, eventType = "test.confirmation.seed", payload = "{}"),
            )
        }

        pathTo(status).forEach { target ->
            val transitioned = payment.transitionTo(target, clock = clock)
            payment = onEventLoop {
                repository.update(
                    transitioned,
                    OutboxMessage(
                        aggregateId = transitioned.id,
                        eventType = "test.confirmation.seed",
                        payload = "{}",
                    ),
                )
            }
        }
        return payment
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `SETTLED payment returns the rendered confirmation HTML`() {
        val payment = seedPayment(DomesticPaymentStatus.SETTLED)
        fakeRenderPort.renderedHtml = "<html>settled-confirmation</html>"

        Given { this } When {
            get("/api/v1/domestic-payments/${payment.id}/confirmation")
        } Then {
            statusCode(200)
            contentType(org.hamcrest.Matchers.containsString("text/html"))
            body(equalTo("<html>settled-confirmation</html>"))
        }

        assert(fakeRenderPort.lastTemplateCode == "POTVRZENI_O_PLATBE_CS") {
            "expected the CS template by default, got ${fakeRenderPort.lastTemplateCode}"
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `lang=en asks the render port for the EN template`() {
        val payment = seedPayment(DomesticPaymentStatus.SETTLED)

        RestAssured.given()
            .queryParam("lang", "en")
            .`when`().get("/api/v1/domestic-payments/${payment.id}/confirmation")
            .then().statusCode(200)

        assert(fakeRenderPort.lastTemplateCode == "POTVRZENI_O_PLATBE_EN")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a payment that has not SETTLED is rejected with 409`() {
        val payment = seedPayment(DomesticPaymentStatus.SENT_TO_CLEARING)

        Given { this } When {
            get("/api/v1/domestic-payments/${payment.id}/confirmation")
        } Then {
            statusCode(409)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an unknown payment id answers 404`() {
        Given { this } When {
            get("/api/v1/domestic-payments/${UUID.randomUUID()}/confirmation")
        } Then {
            statusCode(404)
        }
    }

    @Test
    fun `anonymous request is rejected before the use case runs`() {
        Given { this } When {
            get("/api/v1/domestic-payments/${UUID.randomUUID()}/confirmation")
        } Then {
            statusCode(401)
        }
    }
}
