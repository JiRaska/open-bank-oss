// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.psd2.infrastructure.client

import com.openbank.psd2.application.port.out.AccountServiceClient
import com.openbank.psd2.application.port.out.ConsentServiceClient
import com.openbank.psd2.application.port.out.ConsentSnapshot
import com.openbank.psd2.application.port.out.TppWebhookPublisher
import com.openbank.psd2.application.port.out.TransactionServiceClient
import com.openbank.psd2.domain.model.BookingStatus
import com.openbank.psd2.domain.model.ObAccount
import com.openbank.psd2.domain.model.ObAmount
import com.openbank.psd2.domain.model.ObBalance
import com.openbank.psd2.domain.model.ObTransaction
import com.openbank.psd2.domain.model.PaymentStatus
import com.openbank.psd2.domain.model.TppWebhookEvent
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class StubAccountServiceClient(private val clock: Clock) : AccountServiceClient {
    private val log = Logger.getLogger(StubAccountServiceClient::class.java)

    override suspend fun getAccountsByParty(partyId: String): List<ObAccount> {
        log.infof("getAccountsByParty partyId=%s", partyId)
        return listOf(
            ObAccount("acc-001", "CZ6508000000192000145399", "CZK", "Jan Novák", "Běžný účet", "CURRENT", "CACC"),
        )
    }

    override suspend fun getAccountById(accountId: String): ObAccount? =
        ObAccount(accountId, "CZ6508000000192000145399", "CZK", "Jan Novák", "Běžný účet", "CURRENT", "CACC")

    override suspend fun getBalances(accountId: String): List<ObBalance> = listOf(
        ObBalance(ObAmount("CZK", BigDecimal("12500.00")), "closingBooked", OffsetDateTime.now(clock), LocalDate.now(clock)),
        ObBalance(ObAmount("CZK", BigDecimal("12500.00")), "expected", OffsetDateTime.now(clock), LocalDate.now(clock)),
    )

    override suspend fun getTransactions(
        accountId: String,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        bookingStatus: BookingStatus,
        limit: Int,
        afterCursor: String?,
    ): Pair<List<ObTransaction>, String?> = Pair(emptyList(), null)
}

@ApplicationScoped
class StubConsentServiceClient : ConsentServiceClient {
    private val log = Logger.getLogger(StubConsentServiceClient::class.java)

    override suspend fun getConsent(consentId: String): ConsentSnapshot = ConsentSnapshot(
        consentId = consentId,
        partyId = consentId,
        status = "ACTIVE",
    )

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
    ): String {
        val id = UUID.randomUUID().toString()
        log.infof("createConsent granteeId=%s id=%s", granteeId, id)
        return id
    }

    override suspend fun getConsentStatus(consentId: String): String = "ACTIVE"

    override suspend fun validateConsent(consentId: String, granteeId: String, scope: String, iban: String?): Boolean {
        log.infof("validateConsent consentId=%s scope=%s", consentId, scope)
        return true
    }

    override suspend fun revokeConsent(consentId: String, granteeId: String) {
        log.infof("revokeConsent consentId=%s granteeId=%s", consentId, granteeId)
    }
}

@ApplicationScoped
class StubTransactionServiceClient : TransactionServiceClient {
    private val log = Logger.getLogger(StubTransactionServiceClient::class.java)

    override suspend fun initiatePayment(
        debtorIban: String,
        creditorIban: String,
        creditorName: String,
        amount: BigDecimal,
        currency: String,
        endToEndId: String?,
        remittanceInfo: String?,
        idempotencyKey: String,
    ): String {
        val id = UUID.randomUUID().toString()
        log.debugf(
            "initiatePayment debtor=****%s creditor=****%s id=%s",
            debtorIban.takeLast(4),
            creditorIban.takeLast(4),
            id,
        )
        return id
    }

    override suspend fun getPaymentStatus(paymentId: String): PaymentStatus = PaymentStatus.ACSC
}

@ApplicationScoped
class KafkaTppWebhookPublisher : TppWebhookPublisher {
    private val log = Logger.getLogger(KafkaTppWebhookPublisher::class.java)

    override suspend fun publish(tppId: String, event: TppWebhookEvent) {
        log.infof("webhook tppId=%s eventType=%s resourceId=%s", tppId, event.eventType, event.resourceId)
    }
}
