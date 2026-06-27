// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.application.port.`in`

import com.openbank.psd2.domain.model.BookingStatus
import com.openbank.psd2.domain.model.ConsentStatusOb
import com.openbank.psd2.domain.model.ObAccount
import com.openbank.psd2.domain.model.ObBalance
import com.openbank.psd2.domain.model.ObConsentRequest
import com.openbank.psd2.domain.model.ObConsentResponse
import com.openbank.psd2.domain.model.ObTransaction
import com.openbank.psd2.domain.model.PaymentInitiationResponse
import com.openbank.psd2.domain.model.PaymentProduct
import com.openbank.psd2.domain.model.PaymentStatus
import java.time.LocalDate

data class GetAccountsQuery(val consentId: String, val tppId: String)
data class GetBalancesQuery(val consentId: String, val tppId: String, val accountId: String)
data class GetTransactionsQuery(
    val consentId: String,
    val tppId: String,
    val accountId: String,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val bookingStatus: BookingStatus,
    val limit: Int = 50,
    val afterCursor: String? = null,
)

data class CreateConsentCommand(
    val tppId: String,
    val tppName: String,
    val request: ObConsentRequest,
    val redirectUri: String?,
    val tppTransactionId: String?,
    val ipAddress: String?,
)

data class GetConsentQuery(val consentId: String, val tppId: String)
data class DeleteConsentCommand(val consentId: String, val tppId: String)

data class InitiatePaymentCommand(
    val tppId: String,
    val consentId: String,
    val product: PaymentProduct,
    val payment: Any,
    val idempotencyKey: String,
)

data class GetPaymentStatusQuery(val paymentId: String, val tppId: String, val product: PaymentProduct)

interface AccountInformationUseCase {
    suspend fun getAccounts(query: GetAccountsQuery): List<ObAccount>
    suspend fun getBalances(query: GetBalancesQuery): List<ObBalance>
    suspend fun getTransactions(query: GetTransactionsQuery): TransactionPage
}

data class TransactionPage(val booked: List<ObTransaction>, val pending: List<ObTransaction>, val nextCursor: String?)

interface ConsentManagementUseCase {
    suspend fun createConsent(command: CreateConsentCommand): ObConsentResponse
    suspend fun getConsent(query: GetConsentQuery): ObConsentResponse
    suspend fun deleteConsent(command: DeleteConsentCommand)
    suspend fun getConsentStatus(query: GetConsentQuery): ConsentStatusOb
}

interface PaymentInitiationUseCase {
    suspend fun initiatePayment(command: InitiatePaymentCommand): PaymentInitiationResponse
    suspend fun getPaymentStatus(query: GetPaymentStatusQuery): PaymentStatus
}
