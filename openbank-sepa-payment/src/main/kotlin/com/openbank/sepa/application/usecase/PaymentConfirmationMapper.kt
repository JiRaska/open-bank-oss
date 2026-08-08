// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.usecase

import com.openbank.sepa.domain.model.SepaPayment

/**
 * Builds the Handlebars data map the `POTVRZENI_O_PLATBE_CS`/`POTVRZENI_O_PLATBE_EN` document-service
 * templates expect (ADR-0248 #3), read entirely from the payment's own already-persisted record —
 * that record is the durable evidentiary copy, so document-service never becomes a second one.
 * SEPA has no separate SCA-evidence reference field today, so `scaEvidenceRef` is always `null`.
 */
fun SepaPayment.toConfirmationData(): Map<String, Any?> = mapOf(
    "document" to mapOf(
        "paymentReference" to id.toString(),
        "endToEndId" to endToEndId,
        "executedAt" to (completedAt?.toString() ?: ""),
        "amount" to amount.toPlainString(),
        "currency" to currency,
        "debtorIban" to debtorIban,
        "creditorIban" to creditorIban,
        "creditorName" to creditorName,
        "remittanceInfo" to (remittanceInfo ?: ""),
        "status" to status.name,
        "scaEvidenceRef" to null,
    ),
)
