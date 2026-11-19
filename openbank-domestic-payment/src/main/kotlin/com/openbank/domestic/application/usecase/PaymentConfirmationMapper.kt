// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus

/**
 * Maps a [DomesticPayment] onto the Handlebars `document.*` data shape the
 * `POTVRZENI_O_PLATBE_CS`/`POTVRZENI_O_PLATBE_EN` document-service templates expect (ADR-0248 #3).
 *
 * Pure — zero framework imports, so it is unit-testable without a running service. The payment's
 * own already-persisted record is the sole data source (ADR-0248 Decision §3: "the originating
 * payment service ... invokes document-service's non-persisting preview endpoint at request time
 * with data read from its own already-persisted payment record").
 */
object PaymentConfirmationMapper {

    const val TEMPLATE_CODE_CS = "POTVRZENI_O_PLATBE_CS"
    const val TEMPLATE_CODE_EN = "POTVRZENI_O_PLATBE_EN"

    private const val EN_LANG = "en"

    /** `lang` query param -> template code. Anything other than `en` (case-insensitive) is CS. */
    fun templateCodeFor(lang: String?): String =
        if (lang?.trim()?.lowercase() == EN_LANG) TEMPLATE_CODE_EN else TEMPLATE_CODE_CS

    /**
     * A confirmation is only meaningful for a [DomesticPaymentStatus.SETTLED] payment — the caller
     * must check [DomesticPayment.status] before calling this (it throws [IllegalArgumentException]
     * otherwise, so a caller cannot accidentally render a confirmation for a payment that hasn't
     * settled, or has since REJECTED/RETURNED/CANCELLED).
     */
    fun toConfirmationData(payment: DomesticPayment): Map<String, Any?> {
        require(payment.status == DomesticPaymentStatus.SETTLED) {
            "Payment confirmation is only available for a SETTLED payment (was ${payment.status})"
        }
        val executedAt = payment.settledAt ?: payment.updatedAt
        val remittanceInfo = payment.variableSymbol?.takeIf { it.isNotBlank() }
            ?: payment.messageForPayee?.takeIf { it.isNotBlank() }
            ?: ""

        return mapOf(
            "document" to mapOf(
                "paymentReference" to payment.id.toString(),
                "endToEndId" to payment.endToEndId,
                "executedAt" to executedAt.toString(),
                "amount" to payment.amount.amount.toPlainString(),
                "currency" to payment.currency,
                "debtorIban" to toCzIban(payment.debtorAccountNumber, payment.debtorBankCode),
                "creditorIban" to toCzIban(payment.creditorAccountNumber, payment.creditorBankCode),
                "creditorName" to payment.creditorName,
                "remittanceInfo" to remittanceInfo,
                "status" to payment.status.name,
                // No SCA-evidence-reference field exists on the DomesticPayment aggregate today —
                // never fabricate one; the template renders this slot conditionally (Handlebars
                // `{{#if document.scaEvidenceRef}}`).
                "scaEvidenceRef" to null,
            ),
        )
    }

    /**
     * The IBAN shown on the confirmation, from the service's one builder ([CzechDomesticIban],
     * #16772) so the document names exactly the account the rail used. Coordinates that denote
     * no issuable account are shown as entered (`number/bankCode`), never as a fabricated IBAN.
     */
    private fun toCzIban(accountNumber: String, bankCode: String): String =
        CzechDomesticIban.canonical(accountNumber, bankCode) ?: "$accountNumber/$bankCode"
}
