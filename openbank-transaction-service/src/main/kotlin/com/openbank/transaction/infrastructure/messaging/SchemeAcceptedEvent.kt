// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.messaging

import com.openbank.libs.domain.payment.PaymentRail
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Inbound Kafka event emitted by a payment rail when the scheme accepts the payment
 * (pacs.002 ACSC verdict, ADR-0108). Carries all fields transaction-service needs to
 * initiate the ledger-booking saga without a second round-trip to the rail.
 *
 * [creditorAccountId] is null for inter-bank outgoing transfers where the creditor holds
 * their account at another institution — the booking leg is DEBIT deposit-control(debtor) /
 * CREDIT cash-clearing (ADR-0039 outgoing journal).
 */
data class SchemeAcceptedEvent(
    val paymentId: UUID,
    val debtorAccountId: UUID,
    val creditorAccountId: UUID?,
    val debtorIban: String,
    val creditorIban: String,
    val amount: BigDecimal,
    val currency: String,
    val valueDate: LocalDate,
    val rail: PaymentRail,
)
