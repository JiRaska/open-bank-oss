// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.application.port.out

import com.openbank.psd2.domain.model.BookingStatus
import com.openbank.psd2.domain.model.ObAccount
import com.openbank.psd2.domain.model.ObBalance
import com.openbank.psd2.domain.model.ObTransaction
import com.openbank.psd2.domain.model.PaymentStatus
import com.openbank.psd2.domain.model.TppWebhookEvent
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Outbound port to account-service, exposing the read endpoints the PSD2 AISP facade projects into
 * the Open Banking account/balance/transaction resources.
 */
interface AccountServiceClient {
    suspend fun getAccountsByParty(partyId: String): List<ObAccount>

    suspend fun getAccountById(accountId: String): ObAccount?

    suspend fun getBalances(accountId: String): List<ObBalance>

    /** Returns the page of transactions plus the next-page cursor (null when exhausted). */
    suspend fun getTransactions(
        accountId: String,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        bookingStatus: BookingStatus,
        limit: Int,
        afterCursor: String?,
    ): Pair<List<ObTransaction>, String?>
}

/** A minimal projection of a consent as seen by the PSD2 facade. */
data class ConsentSnapshot(val consentId: String, val partyId: String, val status: String)

/**
 * Outbound port to consent-service, backing the PSD2 consent (AIS) lifecycle: creation, status
 * lookup, per-resource validation enforced before every AISP read, and revocation.
 */
interface ConsentServiceClient {
    suspend fun getConsent(consentId: String): ConsentSnapshot

    @Suppress("LongParameterList")
    suspend fun createConsent(
        partyId: String,
        granteeId: String,
        granteeName: String,
        scopes: Set<String>,
        accountIbans: List<String>?,
        validUntil: LocalDate,
        redirectUri: String?,
        tppTransactionId: String?,
        ipAddress: String?,
    ): String

    suspend fun getConsentStatus(consentId: String): String

    suspend fun validateConsent(consentId: String, granteeId: String, scope: String, iban: String?): Boolean

    suspend fun revokeConsent(consentId: String, granteeId: String)
}

/**
 * Outbound port to transaction-service, backing the PSD2 payment-initiation (PIS) flow: submit a
 * payment and poll its settlement status.
 */
interface TransactionServiceClient {
    suspend fun initiatePayment(
        debtorIban: String,
        creditorIban: String,
        creditorName: String,
        amount: BigDecimal,
        currency: String,
        endToEndId: String?,
        remittanceInfo: String?,
        idempotencyKey: String,
    ): String

    suspend fun getPaymentStatus(paymentId: String): PaymentStatus
}

/** Outbound port for delivering asynchronous event notifications to a registered TPP webhook. */
interface TppWebhookPublisher {
    suspend fun publish(tppId: String, event: TppWebhookEvent)
}
