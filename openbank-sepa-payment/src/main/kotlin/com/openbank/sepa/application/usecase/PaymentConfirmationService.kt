// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.usecase

import com.openbank.sepa.application.port.`in`.PaymentConfirmationDocument
import com.openbank.sepa.application.port.`in`.PaymentConfirmationUseCase
import com.openbank.sepa.application.port.`in`.SepaPaymentUseCase
import com.openbank.sepa.application.port.out.DocumentPreviewPort
import com.openbank.sepa.domain.model.SepaPaymentStatus
import jakarta.enterprise.context.ApplicationScoped
import java.util.Locale
import java.util.UUID

/** The payment exists but has not reached `COMPLETED` yet — there is nothing to confirm. */
class PaymentNotCompletedException(paymentId: UUID, status: SepaPaymentStatus) :
    RuntimeException("SEPA payment $paymentId is $status, not COMPLETED — no confirmation available")

/**
 * ADR-0248 #3 — payment confirmation, rendered synchronously and only on explicit customer
 * request. Strictly additive and read-only: it never transitions the payment or touches
 * settlement/scheme logic, it only reads the payment's own already-persisted status record
 * (via [SepaPaymentUseCase.getPayment]) and hands it to document-service's non-persisting
 * preview endpoint through [DocumentPreviewPort]. Nothing is cached or stored anywhere.
 */
@ApplicationScoped
class PaymentConfirmationService(
    private val paymentUseCase: SepaPaymentUseCase,
    private val documentPreviewPort: DocumentPreviewPort,
) : PaymentConfirmationUseCase {

    companion object {
        private const val TEMPLATE_CODE_CS = "POTVRZENI_O_PLATBE_CS"
        private const val TEMPLATE_CODE_EN = "POTVRZENI_O_PLATBE_EN"
        private const val LOCALE_CS = "cs"
    }

    override suspend fun getConfirmation(paymentId: UUID, locale: String): PaymentConfirmationDocument {
        val payment = paymentUseCase.getPayment(paymentId)
        if (payment.status != SepaPaymentStatus.COMPLETED) {
            throw PaymentNotCompletedException(paymentId, payment.status)
        }

        val templateCode = if (locale.trim().lowercase(Locale.ROOT) == LOCALE_CS) TEMPLATE_CODE_CS else TEMPLATE_CODE_EN
        val renderedHtml = documentPreviewPort.renderTemplate(templateCode, payment.toConfirmationData())

        return PaymentConfirmationDocument(
            contentType = "text/html; charset=UTF-8",
            fileName = "payment-confirmation-$paymentId.html",
            bytes = renderedHtml.toByteArray(Charsets.UTF_8),
        )
    }
}
