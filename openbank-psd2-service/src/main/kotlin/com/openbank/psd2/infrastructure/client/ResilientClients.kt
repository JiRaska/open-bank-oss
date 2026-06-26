// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.psd2.infrastructure.client

import com.openbank.psd2.application.port.out.AccountServiceClient
import com.openbank.psd2.application.port.out.ConsentServiceClient
import com.openbank.psd2.application.port.out.ConsentSnapshot
import com.openbank.psd2.application.port.out.TransactionServiceClient
import com.openbank.psd2.domain.model.BookingStatus
import com.openbank.psd2.domain.model.ObAccount
import com.openbank.psd2.domain.model.ObBalance
import com.openbank.psd2.domain.model.ObTransaction
import com.openbank.psd2.domain.model.PaymentStatus
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Fallback
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.LocalDate

@ApplicationScoped
@Alternative
@Priority(10)
class ResilientAccountServiceClient(private val delegate: StubAccountServiceClient) : AccountServiceClient {

    private val log = Logger.getLogger(ResilientAccountServiceClient::class.java)

    @CircuitBreaker(
        requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 5000,
        successThreshold = 2,
    )
    @Retry(maxRetries = 3, delay = 200, jitter = 100, retryOn = [Exception::class])
    @Timeout(3000)
    @Fallback(fallbackMethod = "getAccountsByPartyFallback")
    override suspend fun getAccountsByParty(partyId: String): List<ObAccount> = delegate.getAccountsByParty(partyId)

    suspend fun getAccountsByPartyFallback(partyId: String): List<ObAccount> {
        log.warnf("getAccountsByParty fallback triggered for partyId=%s", partyId)
        return emptyList()
    }

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 3, delay = 200, jitter = 100)
    @Timeout(3000)
    @Fallback(fallbackMethod = "getAccountByIdFallback")
    override suspend fun getAccountById(accountId: String): ObAccount? = delegate.getAccountById(accountId)

    suspend fun getAccountByIdFallback(accountId: String): ObAccount? {
        log.warnf("getAccountById fallback triggered for accountId=%s", accountId)
        return null
    }

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 3, delay = 200, jitter = 100)
    @Timeout(3000)
    @Fallback(fallbackMethod = "getBalancesFallback")
    override suspend fun getBalances(accountId: String): List<ObBalance> = delegate.getBalances(accountId)

    suspend fun getBalancesFallback(accountId: String): List<ObBalance> {
        log.warnf("getBalances fallback triggered for accountId=%s", accountId)
        return emptyList()
    }

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 300, jitter = 100)
    @Timeout(5000)
    @Fallback(fallbackMethod = "getTransactionsFallback")
    override suspend fun getTransactions(
        accountId: String,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        bookingStatus: BookingStatus,
        limit: Int,
        afterCursor: String?,
    ): Pair<List<ObTransaction>, String?> =
        delegate.getTransactions(accountId, dateFrom, dateTo, bookingStatus, limit, afterCursor)

    suspend fun getTransactionsFallback(
        accountId: String,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        bookingStatus: BookingStatus,
        limit: Int,
        afterCursor: String?,
    ): Pair<List<ObTransaction>, String?> {
        log.warnf("getTransactions fallback triggered for accountId=%s", accountId)
        return Pair(emptyList(), null)
    }
}

@ApplicationScoped
@Alternative
@Priority(10)
class ResilientConsentServiceClient(private val delegate: StubConsentServiceClient) : ConsentServiceClient {

    private val log = Logger.getLogger(ResilientConsentServiceClient::class.java)

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 3, delay = 200, jitter = 100)
    @Timeout(3000)
    @Fallback(fallbackMethod = "getConsentFallback")
    override suspend fun getConsent(consentId: String): ConsentSnapshot = delegate.getConsent(consentId)

    suspend fun getConsentFallback(consentId: String): ConsentSnapshot {
        log.warnf("getConsent fallback for consentId=%s", consentId)
        return ConsentSnapshot(consentId = consentId, partyId = consentId, status = "UNKNOWN")
    }

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 3, delay = 200, jitter = 100)
    @Timeout(3000)
    override suspend fun createConsent(
        partyId: String,
        granteeId: String,
        granteeName: String,
        scopes: Set<String>,
        accountIbans: List<String>?,
        validUntil: LocalDate,
        redirectUri: String?,
        tppTransactionId: String?,
        ipAddress: String?,
    ): String = delegate.createConsent(
        partyId, granteeId, granteeName, scopes, accountIbans,
        validUntil, redirectUri, tppTransactionId, ipAddress,
    )

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 3, delay = 200, jitter = 100)
    @Timeout(2000)
    @Fallback(fallbackMethod = "getConsentStatusFallback")
    override suspend fun getConsentStatus(consentId: String): String = delegate.getConsentStatus(consentId)

    suspend fun getConsentStatusFallback(consentId: String): String {
        log.warnf("getConsentStatus fallback for consentId=%s", consentId)
        return "UNKNOWN"
    }

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 3, delay = 200, jitter = 100)
    @Timeout(2000)
    @Fallback(fallbackMethod = "validateConsentFallback")
    override suspend fun validateConsent(consentId: String, granteeId: String, scope: String, iban: String?): Boolean =
        delegate.validateConsent(consentId, granteeId, scope, iban)

    suspend fun validateConsentFallback(consentId: String, granteeId: String, scope: String, iban: String?): Boolean {
        log.errorf("validateConsent fallback — denying access for consentId=%s scope=%s", consentId, scope)
        return false
    }

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 300, jitter = 100)
    @Timeout(2000)
    override suspend fun revokeConsent(consentId: String, granteeId: String) =
        delegate.revokeConsent(consentId, granteeId)
}

@ApplicationScoped
@Alternative
@Priority(10)
class ResilientTransactionServiceClient(private val delegate: StubTransactionServiceClient) : TransactionServiceClient {

    private val log = Logger.getLogger(ResilientTransactionServiceClient::class.java)

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.3, delay = 10000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 500, jitter = 200)
    @Timeout(10000)
    override suspend fun initiatePayment(
        debtorIban: String,
        creditorIban: String,
        creditorName: String,
        amount: BigDecimal,
        currency: String,
        endToEndId: String?,
        remittanceInfo: String?,
        idempotencyKey: String,
    ): String = delegate.initiatePayment(
        debtorIban,
        creditorIban,
        creditorName,
        amount,
        currency,
        endToEndId,
        remittanceInfo,
        idempotencyKey,
    )

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 3, delay = 200, jitter = 100)
    @Timeout(3000)
    @Fallback(fallbackMethod = "getPaymentStatusFallback")
    override suspend fun getPaymentStatus(paymentId: String): PaymentStatus = delegate.getPaymentStatus(paymentId)

    suspend fun getPaymentStatusFallback(paymentId: String): PaymentStatus {
        log.warnf("getPaymentStatus fallback for paymentId=%s", paymentId)
        return PaymentStatus.PDNG
    }
}
