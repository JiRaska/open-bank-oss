// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.PaymentConfirmationUseCase
import com.openbank.domestic.application.port.`in`.PaymentNotSettledException
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.port.out.PaymentConfirmationRenderPort
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Renders a payment confirmation on demand (ADR-0248 #3) — synchronous, customer-request-only,
 * never persisted. Read-only against [DomesticPaymentRepository]: no [DomesticPaymentRepository.save]
 * or [DomesticPaymentRepository.update] call anywhere in this class, so this use case cannot affect
 * payment state.
 */
@ApplicationScoped
class PaymentConfirmationService(
    private val paymentRepository: DomesticPaymentRepository,
    private val renderPort: PaymentConfirmationRenderPort,
) : PaymentConfirmationUseCase {

    override suspend fun getConfirmation(paymentId: UUID, lang: String?): String {
        val payment = paymentRepository.findById(paymentId)
            ?: throw DomesticPaymentNotFoundException(paymentId)

        if (payment.status != DomesticPaymentStatus.SETTLED) {
            throw PaymentNotSettledException(
                "Payment confirmation is only available once a payment has SETTLED " +
                    "(payment $paymentId is ${payment.status})",
            )
        }

        val templateCode = PaymentConfirmationMapper.templateCodeFor(lang)
        val data = PaymentConfirmationMapper.toConfirmationData(payment)
        return renderPort.renderConfirmation(templateCode, data)
    }
}
