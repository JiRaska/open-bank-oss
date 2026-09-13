// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.domain.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// ─── Enums ───────────────────────────────────────────────────────────────────

enum class PaymentProduct {
    SEPA_CREDIT_TRANSFERS,
    INSTANT_SEPA_CREDIT_TRANSFERS,
    DOMESTIC_CZ, // ČOBS: domácí platba CZ (Kč, tuzemský mezibankovní převod)
    SIPO, // ČOBS: SIPO (Sdružené inkaso plateb obyvatelstva)
}

enum class PaymentStatus {
    RCVD, // Received
    PDNG, // Pending
    ACTC, // AcceptedTechnicalValidation
    ACSC, // AcceptedSettlementCompleted
    RJCT, // Rejected
    CANC, // Cancelled
}

enum class ConsentStatusOb {
    RECEIVED,
    REJECTED,
    VALID,
    REVOKED_BY_PSU,
    EXPIRED,
    TERMINATED_BY_ASPSP,
    PARTIALLY_AUTHORISED,
}

enum class BookingStatus {
    BOOKED,
    PENDING,
    BOTH,
}

// ─── Account Information ──────────────────────────────────────────────────────

data class ObAccount(
    val resourceId: String,
    val iban: String,
    val currency: String,
    val ownerName: String?,
    val name: String?,
    val product: String?,
    val cashAccountType: String?,
)

data class ObBalance(
    val balanceAmount: ObAmount,
    val balanceType: String,
    val lastChangeDateTime: OffsetDateTime?,
    val referenceDate: LocalDate?,
)

data class ObAmount(val currency: String, val amount: BigDecimal)

data class ObTransaction(
    val transactionId: String?,
    val entryReference: String?,
    val bookingDate: LocalDate?,
    val valueDate: LocalDate?,
    val transactionAmount: ObAmount,
    val creditorName: String?,
    val creditorAccount: ObAccountRef?,
    val debtorName: String?,
    val debtorAccount: ObAccountRef?,
    val remittanceInformationUnstructured: String?,
    val bankTransactionCode: String?,
    val bookingStatus: String,
)

data class ObAccountRef(
    val iban: String?,
    val bban: String?,
    val pan: String?,
    val maskedPan: String?,
    val msisdn: String?,
    val currency: String?,
)

// ─── Payment Initiation ───────────────────────────────────────────────────────

data class PaymentInitiation(
    val endToEndIdentification: String?,
    val debtorAccount: ObAccountRef,
    val instructedAmount: ObAmount,
    val creditorAccount: ObAccountRef,
    val creditorName: String,
    val creditorAddress: ObAddress?,
    val remittanceInformationUnstructured: String?,
    val requestedExecutionDate: LocalDate?,
)

data class DomesticCzPayment(
    val endToEndIdentification: String?,
    val debtorAccount: ObAccountRef,
    val instructedAmount: ObAmount,
    val creditorAccount: ObAccountRef,
    val creditorName: String,
    val variableSymbol: String?,
    val specificSymbol: String?,
    val constantSymbol: String?,
    val remittanceInformationUnstructured: String?,
    val requestedExecutionDate: LocalDate?,
)

data class SipoPayment(
    val debtorAccount: ObAccountRef,
    val sipoNumber: String,
    val variableSymbol: String?,
    val requestedExecutionDate: LocalDate?,
)

data class ObAddress(
    val streetName: String?,
    val buildingNumber: String?,
    val city: String?,
    val postalCode: String?,
    val country: String,
)

data class PaymentInitiationResponse(
    val paymentId: String,
    val transactionStatus: PaymentStatus,
    val scaStatus: String?,
    val links: ObLinks,
)

// ─── Consent ─────────────────────────────────────────────────────────────────

data class ObConsentRequest(
    val access: ObAccess,
    val recurringIndicator: Boolean,
    val validUntil: LocalDate,
    val frequencyPerDay: Int,
    val combinedServiceIndicator: Boolean = false,
)

// Element types are nullable ON PURPOSE (#7867): Jackson's Kotlin module null-checks
// constructor parameters but not collection elements, so `{"accounts": [null]}` arrives
// as a list holding a null. Only a nullable element type lets the guard in
// ConsentManagementService reject it with a 400 instead of an NPE-driven 500.
data class ObAccess(
    val accounts: List<ObAccountRef?>?,
    val balances: List<ObAccountRef?>?,
    val transactions: List<ObAccountRef?>?,
    val additionalInformation: ObAdditionalInformation?,
)

data class ObAdditionalInformation(
    val ownerName: List<ObAccountRef>?,
    val trustedBeneficiaries: List<ObAccountRef>?,
    val standingOrders: List<ObAccountRef>?, // ČOBS extension
    val directDebits: List<ObAccountRef>?, // ČOBS extension
)

data class ObConsentResponse(
    val consentId: String,
    val consentStatus: ConsentStatusOb,
    val access: ObAccess,
    val recurringIndicator: Boolean,
    val validUntil: LocalDate,
    val frequencyPerDay: Int,
    val lastActionDate: LocalDate?,
    val links: ObLinks,
)

// ─── Links (HATEOAS) ─────────────────────────────────────────────────────────

data class ObLinks(
    val self: String,
    val status: String? = null,
    val scaOAuth: String? = null,
    val scaRedirect: String? = null,
    val startAuthorisation: String? = null,
    val account: String? = null,
)

// ─── Webhook (ČOBS povinné) ───────────────────────────────────────────────────

data class TppWebhookEvent(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: TppEventType,
    val resourceId: String,
    val resourceType: String,
    val timestamp: OffsetDateTime,
)

enum class TppEventType {
    TRANSACTION_REPORT,
    CONSENT_REVOKED,
    PAYMENT_STATUS_CHANGED,
    ACCOUNT_STATUS_CHANGED,
}
