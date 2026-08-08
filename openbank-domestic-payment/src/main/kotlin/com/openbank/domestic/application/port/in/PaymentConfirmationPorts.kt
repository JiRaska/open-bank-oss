// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.`in`

import java.util.UUID

/**
 * Inbound port for the customer-facing "download confirmation" action (ADR-0248 #3). Strictly
 * additive and read-only: never touches [com.openbank.domestic.domain.model.DomesticPayment]
 * state, never persists anything.
 */
interface PaymentConfirmationUseCase {
    /**
     * @param lang `"en"` for [com.openbank.domestic.application.usecase.PaymentConfirmationMapper.TEMPLATE_CODE_EN],
     * anything else (including null) for the CS template.
     * @return the rendered confirmation HTML.
     * @throws com.openbank.domestic.application.usecase.DomesticPaymentNotFoundException if [paymentId] does not exist.
     * @throws PaymentNotSettledException if the payment has not reached SETTLED.
     */
    suspend fun getConfirmation(paymentId: UUID, lang: String?): String
}

class PaymentNotSettledException(message: String) : RuntimeException(message)
