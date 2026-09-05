// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.application.usecase

import com.openbank.psd2.application.port.`in`.AccountInformationUseCase
import com.openbank.psd2.application.port.`in`.ConsentManagementUseCase
import com.openbank.psd2.application.port.`in`.CreateConsentCommand
import com.openbank.psd2.application.port.`in`.DeleteConsentCommand
import com.openbank.psd2.application.port.`in`.GetAccountsQuery
import com.openbank.psd2.application.port.`in`.GetBalancesQuery
import com.openbank.psd2.application.port.`in`.GetConsentQuery
import com.openbank.psd2.application.port.`in`.GetPaymentStatusQuery
import com.openbank.psd2.application.port.`in`.GetTransactionsQuery
import com.openbank.psd2.application.port.`in`.InitiatePaymentCommand
import com.openbank.psd2.application.port.`in`.PaymentInitiationUseCase
import com.openbank.psd2.application.port.`in`.TransactionPage
import com.openbank.psd2.application.port.out.AccountServiceClient
import com.openbank.psd2.application.port.out.ConsentServiceClient
import com.openbank.psd2.application.port.out.TransactionServiceClient
import com.openbank.psd2.domain.model.ConsentStatusOb
import com.openbank.psd2.domain.model.DomesticCzPayment
import com.openbank.psd2.domain.model.ObAccess
import com.openbank.psd2.domain.model.ObAccount
import com.openbank.psd2.domain.model.ObBalance
import com.openbank.psd2.domain.model.ObConsentResponse
import com.openbank.psd2.domain.model.ObLinks
import com.openbank.psd2.domain.model.PaymentInitiation
import com.openbank.psd2.domain.model.PaymentInitiationResponse
import com.openbank.psd2.domain.model.PaymentProduct
import com.openbank.psd2.domain.model.PaymentStatus
import com.openbank.psd2.domain.model.SipoPayment
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.LocalDate

class ConsentNotFoundException(id: String) : RuntimeException("Consent not found: $id")
class ConsentUnauthorizedException(msg: String) : RuntimeException(msg)
class PaymentNotFoundException(id: String) : RuntimeException("Payment not found: $id")
class TppNotAuthorizedException(tppId: String) : RuntimeException("TPP not authorized: $tppId")
class InvalidPaymentProductException(msg: String) : RuntimeException(msg)

// Replaces bare IllegalArgumentException (issue #526): a service-local
// ExceptionMapper<IllegalArgumentException> collided non-deterministically with
// openbank-libs-runtime's own mapper for the identical JDK type — both happen to answer 400,
// but the response BODY shapes differ (this service's Berlin Group NextGenPSD2 `tppMessages`
// array vs libs' generic ApiError envelope), so a TPP client could non-deterministically get
// either shape depending on which provider JAX-RS happened to pick.
class Psd2RequestFormatException(msg: String) : RuntimeException(msg)

@ApplicationScoped
class AccountInformationService(
    private val accountClient: AccountServiceClient,
    private val consentClient: ConsentServiceClient,
) : AccountInformationUseCase {

    override suspend fun getAccounts(query: GetAccountsQuery): List<ObAccount> {
        val valid = consentClient.validateConsent(query.consentId, query.tppId, "ACCOUNTS_READ", null)
        if (!valid) throw ConsentUnauthorizedException("Consent ${query.consentId} does not allow ACCOUNTS_READ")
        val partyId = resolvePartyFromConsent(query.consentId)
        return accountClient.getAccountsByParty(partyId)
    }

    override suspend fun getBalances(query: GetBalancesQuery): List<ObBalance> {
        val valid = consentClient.validateConsent(query.consentId, query.tppId, "BALANCES_READ", query.accountId)
        if (!valid) throw ConsentUnauthorizedException("Consent ${query.consentId} does not allow BALANCES_READ")
        return accountClient.getBalances(query.accountId)
    }

    override suspend fun getTransactions(query: GetTransactionsQuery): TransactionPage {
        val valid = consentClient.validateConsent(query.consentId, query.tppId, "TRANSACTIONS_READ", query.accountId)
        if (!valid) throw ConsentUnauthorizedException("Consent ${query.consentId} does not allow TRANSACTIONS_READ")
        val (txns, nextCursor) = accountClient.getTransactions(
            query.accountId,
            query.dateFrom,
            query.dateTo,
            query.bookingStatus,
            query.limit,
            query.afterCursor,
        )
        val booked = txns.filter { it.bookingStatus == "BOOKED" }
        val pending = txns.filter { it.bookingStatus == "PENDING" }
        return TransactionPage(booked, pending, nextCursor)
    }

    private suspend fun resolvePartyFromConsent(consentId: String): String = consentClient.getConsent(consentId).partyId
}

@ApplicationScoped
class ConsentManagementService(private val consentClient: ConsentServiceClient, private val clock: Clock) :
    ConsentManagementUseCase {

    override suspend fun createConsent(command: CreateConsentCommand): ObConsentResponse {
        requireNoNullAccountRefs(command.request.access)
        val scopes = buildScopes(command.request.access)
        val ibans = collectIbans(command.request.access)
        val validUntil = minOf(command.request.validUntil, LocalDate.now(clock).plusDays(90))

        val consentId = consentClient.createConsent(
            partyId = "",
            granteeId = command.tppId,
            granteeName = command.tppName,
            scopes = scopes,
            accountIbans = ibans,
            validUntil = validUntil,
            redirectUri = command.redirectUri,
            tppTransactionId = command.tppTransactionId,
            ipAddress = command.ipAddress,
        )

        return ObConsentResponse(
            consentId = consentId,
            consentStatus = ConsentStatusOb.RECEIVED,
            access = command.request.access,
            recurringIndicator = command.request.recurringIndicator,
            validUntil = validUntil,
            frequencyPerDay = command.request.frequencyPerDay,
            lastActionDate = LocalDate.now(clock),
            links = ObLinks(
                self = "/open-banking/v2/consents/$consentId",
                status = "/open-banking/v2/consents/$consentId/status",
                scaRedirect = "/open-banking/v2/consents/$consentId/authorisations",
            ),
        )
    }

    override suspend fun getConsent(query: GetConsentQuery): ObConsentResponse {
        val consent = consentClient.getConsent(query.consentId)
        return ObConsentResponse(
            consentId = query.consentId,
            consentStatus = ConsentStatusOb.valueOf(mapStatus(consent.status)),
            access = ObAccess(null, null, null, null),
            recurringIndicator = true,
            validUntil = LocalDate.now(clock).plusDays(90),
            frequencyPerDay = 4,
            lastActionDate = LocalDate.now(clock),
            links = ObLinks(self = "/open-banking/v2/consents/${query.consentId}"),
        )
    }

    override suspend fun deleteConsent(command: DeleteConsentCommand) {
        consentClient.revokeConsent(command.consentId, command.tppId)
    }

    override suspend fun getConsentStatus(query: GetConsentQuery): ConsentStatusOb {
        val status = consentClient.getConsentStatus(query.consentId)
        return ConsentStatusOb.valueOf(mapStatus(status))
    }

    private fun buildScopes(access: ObAccess): Set<String> {
        val scopes = mutableSetOf<String>()
        if (access.accounts != null) scopes.add("ACCOUNTS_READ")
        if (access.balances != null) scopes.add("BALANCES_READ")
        if (access.transactions != null) scopes.add("TRANSACTIONS_READ")
        access.additionalInformation?.standingOrders?.let { scopes.add("STANDING_ORDERS_READ") }
        access.additionalInformation?.directDebits?.let { scopes.add("DIRECT_DEBITS_READ") }
        return scopes
    }

    private fun collectIbans(access: ObAccess): List<String>? {
        val ibans = mutableSetOf<String>()
        access.accounts?.mapNotNull { it?.iban }?.let { ibans.addAll(it) }
        access.balances?.mapNotNull { it?.iban }?.let { ibans.addAll(it) }
        access.transactions?.mapNotNull { it?.iban }?.let { ibans.addAll(it) }
        return if (ibans.isEmpty()) null else ibans.toList()
    }

    /**
     * Reject a `null` JSON array element with a 400 (#7867). Jackson null-checks constructor
     * parameters but not collection elements, so `[null]` deserialises into a list holding a
     * null; without this guard the first dereference was an NPE that `GenericExceptionMapper`
     * rendered as a 500. `IllegalArgumentException` maps to 400 via libs-runtime.
     */
    private fun requireNoNullAccountRefs(access: ObAccess) {
        mapOf(
            "accounts" to access.accounts,
            "balances" to access.balances,
            "transactions" to access.transactions,
        ).forEach { (field, refs) ->
            refs?.forEachIndexed { index, ref ->
                requireNotNull(ref) { "access.$field[$index] must not be null" }
            }
        }
    }

    private fun mapStatus(s: String): String = when (s) {
        "ACTIVE" -> "VALID"
        "PENDING_SCA" -> "RECEIVED"
        "REVOKED" -> "REVOKED_BY_PSU"
        "EXPIRED" -> "EXPIRED"
        "REJECTED" -> "REJECTED"
        else -> "RECEIVED"
    }
}

@ApplicationScoped
class PaymentInitiationService(
    private val transactionClient: TransactionServiceClient,
    private val consentClient: ConsentServiceClient,
) : PaymentInitiationUseCase {

    override suspend fun initiatePayment(command: InitiatePaymentCommand): PaymentInitiationResponse {
        val valid = consentClient.validateConsent(
            command.consentId,
            command.tppId,
            if (command.product == PaymentProduct.DOMESTIC_CZ) {
                "DOMESTIC_PAYMENT_INITIATE"
            } else if (command.product == PaymentProduct.SIPO) {
                "SIPO_PAYMENT_INITIATE"
            } else {
                "PAYMENTS_INITIATE"
            },
            debtorIban(command.payment),
        )
        if (!valid) throw ConsentUnauthorizedException("Consent does not allow payment initiation")

        val paymentId = when (command.product) {
            PaymentProduct.SEPA_CREDIT_TRANSFERS,
            PaymentProduct.INSTANT_SEPA_CREDIT_TRANSFERS,
            -> {
                val p = command.payment as PaymentInitiation
                transactionClient.initiatePayment(
                    debtorIban = p.debtorAccount.iban ?: throw InvalidPaymentProductException("Debtor IBAN required"),
                    creditorIban = p.creditorAccount.iban
                        ?: throw InvalidPaymentProductException("Creditor IBAN required"),
                    creditorName = p.creditorName,
                    amount = p.instructedAmount.amount,
                    currency = p.instructedAmount.currency,
                    endToEndId = p.endToEndIdentification,
                    remittanceInfo = p.remittanceInformationUnstructured,
                    idempotencyKey = command.idempotencyKey,
                )
            }
            PaymentProduct.DOMESTIC_CZ -> {
                val p = command.payment as DomesticCzPayment
                transactionClient.initiatePayment(
                    debtorIban = p.debtorAccount.iban ?: throw InvalidPaymentProductException("Debtor IBAN required"),
                    creditorIban = p.creditorAccount.iban
                        ?: throw InvalidPaymentProductException("Creditor IBAN required"),
                    creditorName = p.creditorName,
                    amount = p.instructedAmount.amount,
                    currency = p.instructedAmount.currency,
                    endToEndId = p.endToEndIdentification,
                    remittanceInfo = listOfNotNull(
                        p.variableSymbol,
                        p.specificSymbol,
                        p.constantSymbol,
                    ).joinToString("/"),
                    idempotencyKey = command.idempotencyKey,
                )
            }
            PaymentProduct.SIPO -> {
                val p = command.payment as SipoPayment
                transactionClient.initiatePayment(
                    debtorIban = p.debtorAccount.iban ?: throw InvalidPaymentProductException("Debtor IBAN required"),
                    creditorIban = "CZ0000000000000000000000",
                    creditorName = "SIPO",
                    amount = java.math.BigDecimal.ZERO,
                    currency = "CZK",
                    endToEndId = p.sipoNumber,
                    remittanceInfo = p.variableSymbol,
                    idempotencyKey = command.idempotencyKey,
                )
            }
        }

        return PaymentInitiationResponse(
            paymentId = paymentId,
            transactionStatus = PaymentStatus.RCVD,
            scaStatus = "received",
            links = ObLinks(
                self = "/open-banking/v2/payments/${command.product.name.lowercase()}/$paymentId",
                status = "/open-banking/v2/payments/${command.product.name.lowercase()}/$paymentId/status",
            ),
        )
    }

    override suspend fun getPaymentStatus(query: GetPaymentStatusQuery): PaymentStatus =
        transactionClient.getPaymentStatus(query.paymentId)

    private fun debtorIban(payment: Any): String? = when (payment) {
        is PaymentInitiation -> payment.debtorAccount.iban
        is DomesticCzPayment -> payment.debtorAccount.iban
        is SipoPayment -> payment.debtorAccount.iban
        else -> null
    }
}
