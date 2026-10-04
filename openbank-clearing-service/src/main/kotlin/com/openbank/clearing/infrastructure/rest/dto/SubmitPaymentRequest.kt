// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.rest.dto

import com.openbank.clearing.domain.model.PaymentRail
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The `POST /api/v1/clearing/submit` body — the same members, names and `currency` default
 * (`EUR`) the endpoint has always accepted. [amount] is nullable so an absent amount reaches
 * `Money.parseInbound` and is answered as a named-field 400 rather than a Jackson error.
 */
data class SubmitPaymentRequest(
    val paymentId: UUID,
    val paymentReference: String,
    val debtorIban: String,
    val creditorIban: String,
    val debtorBic: String? = null,
    val creditorBic: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = "EUR",
    val rail: PaymentRail = PaymentRail.SEPA_SCT,
    val valueDate: LocalDate? = null,
    val endToEndId: String? = null,
    val remittanceInfo: String? = null,
)
