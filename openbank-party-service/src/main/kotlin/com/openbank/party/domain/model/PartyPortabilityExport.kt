// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import java.time.Instant

/**
 * GDPR Art. 20 (Right to Data Portability) export — the scoped, filtered projection of the
 * Art. 15 export decided in ADR-0204.
 *
 * Scope is consent (Art. 6(1)(a)) and contract (Art. 6(1)(b)) data ONLY — the ADR's D1 filter.
 * KYC/AML due-diligence fields, risk ratings, PEP flags and sanctions-screening state are all
 * Art. 6(1)(c) legal-obligation data and are excluded by construction: this model has no field
 * that could carry them, which is why kyc-service is not consulted for this export at all.
 *
 * Contents and their basis:
 *  - [party] + [documents] — subject-provided identity data, Art. 6(1)(b) contract.
 *  - [accounts] with their [PortabilityTransaction]s — payment-services data under the account
 *    contract, Art. 6(1)(b). Per Art. 20(4) the counterparty NAME is retained (the subject needs
 *    to recognise their own transactions) while the counterparty IBAN is redacted to its
 *    bank-code prefix ([redactIban]) — the same level of detail a statement already discloses.
 *  - [cards] — card-product metadata under the card contract, Art. 6(1)(b). Never a PAN: a
 *    portability payload must not become a PCI data store.
 *
 * Format is structured JSON (Art. 20(1) "structured, commonly used, machine-readable"), the
 * same shape the Art. 15 export already uses (ADR-0204 D3). Art. 20(2) direct
 * controller-to-controller transmission is deliberately NOT offered (ADR-0204 D4).
 */
data class PartyPortabilityExport(
    val party: Party,
    val documents: List<PartyDocument>,
    val accounts: List<PortabilityAccount>,
    val cards: List<PortabilityCard>,
    val exportedAt: Instant,
) {
    fun toResponse(): Map<String, Any?> = mapOf(
        "gdprArticle" to "20",
        "scope" to "Art. 6(1)(a) consent + Art. 6(1)(b) contract data only (ADR-0204 D1)",
        "party" to party,
        "documents" to documents,
        "accounts" to accounts.map { it.toResponse() },
        "cards" to cards.map { it.toResponse() },
        "exportedAt" to exportedAt.toString(),
    )
}

data class PortabilityAccount(
    val accountId: String,
    val iban: String,
    val currency: String,
    val productCode: String?,
    val status: String?,
    val transactions: List<PortabilityTransaction>,
) {
    fun toResponse(): Map<String, Any?> = mapOf(
        "accountId" to accountId,
        "iban" to iban,
        "currency" to currency,
        "productCode" to productCode,
        "status" to status,
        "transactions" to transactions.map { it.toResponse() },
    )
}

data class PortabilityTransaction(
    val transactionId: String,
    val bookingDate: String?,
    val amount: String?,
    val currency: String?,
    val type: String?,
    val status: String?,
    /** Null in v1 — transaction-service exposes account UUIDs, not counterparty identity. */
    val counterpartyName: String?,
    /** Null in v1 for the same reason; Art. 20(4) redaction applies once an IBAN is available. */
    val counterpartyIbanRedacted: String?,
    val remittanceInfo: String?,
    /** The booking reference transaction-service assigns (its `referenceNumber`). */
    val reference: String?,
) {
    fun toResponse(): Map<String, Any?> = mapOf(
        "transactionId" to transactionId,
        "bookingDate" to bookingDate,
        "amount" to amount,
        "currency" to currency,
        "type" to type,
        "status" to status,
        "counterpartyName" to counterpartyName,
        "counterpartyIban" to counterpartyIbanRedacted,
        "remittanceInfo" to remittanceInfo,
        "reference" to reference,
    )
}

data class PortabilityCard(
    val cardId: String,
    val productCode: String?,
    val status: String?,
    val expiryMonth: Int?,
    val expiryYear: Int?,
) {
    fun toResponse(): Map<String, Any?> = mapOf(
        "cardId" to cardId,
        "productCode" to productCode,
        "status" to status,
        "expiryMonth" to expiryMonth,
        "expiryYear" to expiryYear,
    )
}

/**
 * Redacts a counterparty IBAN to its issuing-bank prefix (ADR-0204 D2, Art. 20(4)): the
 * country code, check digits and bank code are kept so the subject recognises the destination
 * bank; everything that identifies the counterparty's own account is masked. For a Czech IBAN
 * (`CZkk BBBB XXXXXXXXXXXXXXXX`) the first [IBAN_BANK_PREFIX_LENGTH] characters are exactly
 * that prefix; other IBAN layouts keep the same rule, which always covers
 * country+check+bank-identifier.
 */
fun redactIban(iban: String?): String? = iban
    ?.replace(" ", "")
    ?.let { compact ->
        when {
            compact.length <= IBAN_BANK_PREFIX_LENGTH -> compact
            else -> compact.take(IBAN_BANK_PREFIX_LENGTH) + "*".repeat(compact.length - IBAN_BANK_PREFIX_LENGTH)
        }
    }

private const val IBAN_BANK_PREFIX_LENGTH = 8
