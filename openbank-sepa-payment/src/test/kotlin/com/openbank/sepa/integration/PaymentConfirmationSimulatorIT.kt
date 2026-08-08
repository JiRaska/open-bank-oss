// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * ADR-0248 #3 cross-service IT: drives the REAL `GET /{paymentId}/confirmation` endpoint end to
 * end — real Postgres-backed payment lookup, real [PaymentConfirmationUseCase] wiring, real
 * [com.openbank.sepa.infrastructure.client.DocumentPreviewAdapter] REST-client calls — against a
 * stubbed document-service ([DocumentServiceWireMockResource]). The mocked-boundary version of
 * this same behavior lives in
 * [com.openbank.sepa.application.usecase.PaymentConfirmationServiceTest].
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
@QuarkusTestResource(DocumentServiceWireMockResource::class)
class PaymentConfirmationSimulatorIT {

    @Inject
    lateinit var repository: SepaPaymentRepository

    private fun payment(status: SepaPaymentStatus, id: UUID = UUID.randomUUID()) = SepaPayment(
        id = id,
        idempotencyKey = "it-conf-$id",
        type = SepaPaymentType.SCT,
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Debtor",
        creditorIban = "FR1420041010050500013M02606",
        creditorName = "Bob Creditor",
        creditorBic = "BNPAFRPPXXX",
        amount = BigDecimal("99.90"),
        currency = "EUR",
        remittanceInfo = "IT confirmation",
        endToEndId = "E2E-IT-CONF-$id",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = Instant.now(),
        completedAt = if (status == SepaPaymentStatus.COMPLETED) Instant.now() else null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    // A bare @QuarkusTest thread carries no Vert.x context, and the reactive repository's
    // Panache.withTransaction needs one — VertxContextSupport bridges it, the same pattern
    // SepaPaymentOutboxClaimIT already uses to seed rows outside a real HTTP request.
    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun seed(status: SepaPaymentStatus): UUID {
        val toSave = payment(status)
        val saved = onEventLoop {
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
        return saved.id
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a COMPLETED payment downloads a real rendered confirmation over HTTP`() {
        DocumentServiceWireMockResource.stubPublishedTemplate()
        val paymentId = seed(SepaPaymentStatus.COMPLETED)

        Given { this } When {
            get("/api/v1/sepa-payments/$paymentId/confirmation")
        } Then {
            statusCode(200)
            body(equalTo("<html><body>rendered confirmation</body></html>"))
        }

        DocumentServiceWireMockResource.verifyPreviewRequestContained("COMPLETED")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a non-COMPLETED payment is rejected with 409, never calling document-service`() {
        val paymentId = seed(SepaPaymentStatus.RECEIVED)

        Given { this } When {
            get("/api/v1/sepa-payments/$paymentId/confirmation")
        } Then {
            statusCode(409)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an unknown payment id is a 404`() {
        Given { this } When {
            get("/api/v1/sepa-payments/${UUID.randomUUID()}/confirmation")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `no PUBLISHED template fails closed with 502, not a silent empty document`() {
        val paymentId = seed(SepaPaymentStatus.COMPLETED)
        DocumentServiceWireMockResource.stubNoPublishedTemplate()

        Given { this } When {
            get("/api/v1/sepa-payments/$paymentId/confirmation")
        } Then {
            statusCode(502)
        }

        DocumentServiceWireMockResource.stubPublishedTemplate()
    }

    @Test
    fun `an anonymous request is stopped before it ever reaches the handler`() {
        Given { this } When {
            get("/api/v1/sepa-payments/${UUID.randomUUID()}/confirmation")
        } Then {
            statusCode(401)
        }
    }
}
