// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.PaymentNotSettledException
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.port.out.PaymentConfirmationRenderPort
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Coverage for [PaymentConfirmationService] — the "download confirmation" use case (ADR-0248 #3).
 * Never calls [DomesticPaymentRepository.save]/[DomesticPaymentRepository.update]: this is a
 * read-only view over the payment's own persisted status record.
 */
class PaymentConfirmationServiceTest {

    private lateinit var paymentRepository: DomesticPaymentRepository
    private lateinit var renderPort: PaymentConfirmationRenderPort
    private lateinit var service: PaymentConfirmationService

    @BeforeEach
    fun setUp() {
        paymentRepository = mockk()
        renderPort = mockk()
        service = PaymentConfirmationService(paymentRepository, renderPort)
    }

    @Test
    fun `unknown payment id raises DomesticPaymentNotFoundException`() {
        val paymentId = UUID.randomUUID()
        coEvery { paymentRepository.findById(paymentId) } returns null

        assertThatThrownBy {
            runBlocking { service.getConfirmation(paymentId, lang = null) }
        }.isInstanceOf(DomesticPaymentNotFoundException::class.java)
    }

    @Test
    fun `a payment that has not SETTLED raises PaymentNotSettledException`() {
        val payment = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        coEvery { paymentRepository.findById(payment.id) } returns payment

        assertThatThrownBy {
            runBlocking { service.getConfirmation(payment.id, lang = null) }
        }.isInstanceOf(PaymentNotSettledException::class.java)
    }

    @Test
    fun `a SETTLED payment renders via the CS template by default`() {
        val payment = payment(status = DomesticPaymentStatus.SETTLED)
        coEvery { paymentRepository.findById(payment.id) } returns payment
        coEvery {
            renderPort.renderConfirmation(PaymentConfirmationMapper.TEMPLATE_CODE_CS, any())
        } returns "<html>ok</html>"

        val html = runBlocking { service.getConfirmation(payment.id, lang = null) }

        assertThat(html).isEqualTo("<html>ok</html>")
        coVerify(exactly = 1) { renderPort.renderConfirmation(PaymentConfirmationMapper.TEMPLATE_CODE_CS, any()) }
    }

    @Test
    fun `lang=en renders via the EN template`() {
        val payment = payment(status = DomesticPaymentStatus.SETTLED)
        coEvery { paymentRepository.findById(payment.id) } returns payment
        coEvery {
            renderPort.renderConfirmation(PaymentConfirmationMapper.TEMPLATE_CODE_EN, any())
        } returns "<html>en</html>"

        val html = runBlocking { service.getConfirmation(payment.id, lang = "en") }

        assertThat(html).isEqualTo("<html>en</html>")
    }

    @Test
    fun `never calls save or update - strictly read-only`() {
        val payment = payment(status = DomesticPaymentStatus.SETTLED)
        coEvery { paymentRepository.findById(payment.id) } returns payment
        coEvery { renderPort.renderConfirmation(any(), any()) } returns "<html/>"

        runBlocking { service.getConfirmation(payment.id, lang = null) }

        coVerify(exactly = 0) { paymentRepository.save(any(), any()) }
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    private fun payment(status: DomesticPaymentStatus) = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-${UUID.randomUUID()}",
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Debtor",
        creditorAccountNumber = "0987654321",
        creditorBankCode = "2010",
        creditorName = "Creditor Name",
        amount = BigDecimal("20.00"),
        currency = "CZK",
        variableSymbol = "123456",
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = DomesticPaymentPriority.STANDARD,
        transferScope = DomesticTransferScope.EXTERNAL,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOMS1234567890",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = Instant.parse("2026-08-01T09:00:00Z"),
        settledAt = Instant.parse("2026-08-01T10:00:00Z"),
        createdAt = Instant.parse("2026-08-01T08:00:00Z"),
        updatedAt = Instant.parse("2026-08-01T10:00:00Z"),
    )
}
