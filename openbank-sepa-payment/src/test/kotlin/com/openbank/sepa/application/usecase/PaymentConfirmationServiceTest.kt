// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.usecase

import com.openbank.sepa.application.port.`in`.SepaPaymentUseCase
import com.openbank.sepa.application.port.out.DocumentPreviewPort
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Coverage for [PaymentConfirmationService] with the document-service client mocked out at the
 * [DocumentPreviewPort] boundary — proves the state gate (only `COMPLETED` renders) and the
 * locale-to-template-code routing without any network dependency. The real wire contract to
 * document-service is covered separately by
 * [com.openbank.sepa.integration.PaymentConfirmationSimulatorIT] against a stubbed HTTP server.
 */
class PaymentConfirmationServiceTest {

    private val paymentUseCase = mockk<SepaPaymentUseCase>()
    private val documentPreviewPort = mockk<DocumentPreviewPort>()
    private val service = PaymentConfirmationService(paymentUseCase, documentPreviewPort)

    private fun payment(status: SepaPaymentStatus) = SepaPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-conf-svc",
        type = SepaPaymentType.SCT,
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Debtor",
        creditorIban = "FR1420041010050500013M02606",
        creditorName = "Bob Creditor",
        creditorBic = "BNPAFRPPXXX",
        amount = BigDecimal("50.00"),
        currency = "EUR",
        remittanceInfo = "Invoice 7",
        endToEndId = "E2E-SVC-0001",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = Instant.now(),
        completedAt = if (status == SepaPaymentStatus.COMPLETED) Instant.now() else null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `a COMPLETED payment renders the EN template by default`() {
        val payment = payment(SepaPaymentStatus.COMPLETED)
        coEvery { paymentUseCase.getPayment(payment.id) } returns payment
        coEvery { documentPreviewPort.renderTemplate("POTVRZENI_O_PLATBE_EN", any()) } returns "<html>ok</html>"

        val confirmation = runBlocking { service.getConfirmation(payment.id, "en") }

        assertThat(confirmation.contentType).isEqualTo("text/html; charset=UTF-8")
        assertThat(confirmation.fileName).isEqualTo("payment-confirmation-${payment.id}.html")
        assertThat(String(confirmation.bytes, Charsets.UTF_8)).isEqualTo("<html>ok</html>")
        coVerify(exactly = 1) { documentPreviewPort.renderTemplate("POTVRZENI_O_PLATBE_EN", any()) }
    }

    @Test
    fun `a cs locale renders the CS template`() {
        val payment = payment(SepaPaymentStatus.COMPLETED)
        coEvery { paymentUseCase.getPayment(payment.id) } returns payment
        coEvery { documentPreviewPort.renderTemplate("POTVRZENI_O_PLATBE_CS", any()) } returns "<html>ok-cs</html>"

        runBlocking { service.getConfirmation(payment.id, "CS") }

        coVerify(exactly = 1) { documentPreviewPort.renderTemplate("POTVRZENI_O_PLATBE_CS", any()) }
    }

    @Test
    fun `a non-COMPLETED payment is rejected without ever calling document-service`() {
        val payment = payment(SepaPaymentStatus.PROCESSING)
        coEvery { paymentUseCase.getPayment(payment.id) } returns payment

        assertThatThrownBy { runBlocking { service.getConfirmation(payment.id, "en") } }
            .isInstanceOf(PaymentNotCompletedException::class.java)
            .hasMessageContaining("PROCESSING")

        coVerify(exactly = 0) { documentPreviewPort.renderTemplate(any(), any()) }
    }
}
