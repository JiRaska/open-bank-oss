// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.application.usecase

import com.openbank.psd2.application.port.`in`.CreateConsentCommand
import com.openbank.psd2.application.port.`in`.DeleteConsentCommand
import com.openbank.psd2.application.port.`in`.GetAccountsQuery
import com.openbank.psd2.application.port.`in`.GetBalancesQuery
import com.openbank.psd2.application.port.`in`.GetConsentQuery
import com.openbank.psd2.application.port.`in`.GetPaymentStatusQuery
import com.openbank.psd2.application.port.`in`.GetTransactionsQuery
import com.openbank.psd2.application.port.`in`.InitiatePaymentCommand
import com.openbank.psd2.application.port.`in`.TransactionPage
import com.openbank.psd2.application.port.out.AccountServiceClient
import com.openbank.psd2.application.port.out.ConsentServiceClient
import com.openbank.psd2.application.port.out.ConsentSnapshot
import com.openbank.psd2.application.port.out.TransactionServiceClient
import com.openbank.psd2.domain.model.BookingStatus
import com.openbank.psd2.domain.model.ConsentStatusOb
import com.openbank.psd2.domain.model.DomesticCzPayment
import com.openbank.psd2.domain.model.ObAccess
import com.openbank.psd2.domain.model.ObAccount
import com.openbank.psd2.domain.model.ObAccountRef
import com.openbank.psd2.domain.model.ObAdditionalInformation
import com.openbank.psd2.domain.model.ObAmount
import com.openbank.psd2.domain.model.ObBalance
import com.openbank.psd2.domain.model.ObConsentRequest
import com.openbank.psd2.domain.model.ObLinks
import com.openbank.psd2.domain.model.ObTransaction
import com.openbank.psd2.domain.model.PaymentInitiation
import com.openbank.psd2.domain.model.PaymentProduct
import com.openbank.psd2.domain.model.PaymentStatus
import com.openbank.psd2.domain.model.SipoPayment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class Psd2ServicesTest {

    private val fixedClock = Clock.fixed(Instant.parse("2024-01-15T10:00:00Z"), ZoneOffset.UTC)
    private val fixedToday = LocalDate.of(2024, 1, 15)

    private val accountClient = mockk<AccountServiceClient>()
    private val consentClient = mockk<ConsentServiceClient>()
    private val transactionClient = mockk<TransactionServiceClient>()

    private val accountInformationService = AccountInformationService(accountClient, consentClient)
    private val consentManagementService = ConsentManagementService(consentClient, fixedClock)
    private val paymentInitiationService = PaymentInitiationService(transactionClient, consentClient)

    @Test
    fun `getAccounts validates consent and returns accounts`(): Unit = runBlocking {
        val query = GetAccountsQuery(consentId = "consent-1", tppId = "tpp-1")
        val consent = ConsentSnapshot(consentId = "consent-1", partyId = "party-1", status = "ACTIVE")
        val accounts = listOf(sampleAccount("acc-1"), sampleAccount("acc-2"))

        coEvery { consentClient.validateConsent("consent-1", "tpp-1", "ACCOUNTS_READ", null) } returns true
        coEvery { consentClient.getConsent("consent-1") } returns consent
        coEvery { accountClient.getAccountsByParty("party-1") } returns accounts

        val result = accountInformationService.getAccounts(query)

        assertThat(result).isEqualTo(accounts)
        coVerify(exactly = 1) { consentClient.validateConsent("consent-1", "tpp-1", "ACCOUNTS_READ", null) }
        coVerify(exactly = 1) { consentClient.getConsent("consent-1") }
        coVerify(exactly = 1) { accountClient.getAccountsByParty("party-1") }
    }

    @Test
    fun `getAccounts throws ConsentUnauthorizedException when consent invalid`(): Unit = runBlocking {
        coEvery { consentClient.validateConsent("consent-1", "tpp-1", "ACCOUNTS_READ", null) } returns false

        assertThatThrownBy {
            runBlocking { accountInformationService.getAccounts(GetAccountsQuery("consent-1", "tpp-1")) }
        }
            .isInstanceOf(ConsentUnauthorizedException::class.java)
            .hasMessageContaining("ACCOUNTS_READ")

        coVerify(exactly = 1) { consentClient.validateConsent("consent-1", "tpp-1", "ACCOUNTS_READ", null) }
    }

    @Test
    fun `getBalances validates consent with account scope`(): Unit = runBlocking {
        val query = GetBalancesQuery(consentId = "consent-1", tppId = "tpp-1", accountId = "acc-1")
        val balances = listOf(sampleBalance())

        coEvery { consentClient.validateConsent("consent-1", "tpp-1", "BALANCES_READ", "acc-1") } returns true
        coEvery { accountClient.getBalances("acc-1") } returns balances

        val result = accountInformationService.getBalances(query)

        assertThat(result).isEqualTo(balances)
        coVerify(exactly = 1) { consentClient.validateConsent("consent-1", "tpp-1", "BALANCES_READ", "acc-1") }
        coVerify(exactly = 1) { accountClient.getBalances("acc-1") }
    }

    @Test
    fun `getTransactions returns paged results with booked and pending separation`(): Unit = runBlocking {
        val query = GetTransactionsQuery(
            consentId = "consent-1",
            tppId = "tpp-1",
            accountId = "acc-1",
            dateFrom = LocalDate.of(2024, 1, 1),
            dateTo = LocalDate.of(2024, 1, 31),
            bookingStatus = BookingStatus.BOTH,
            limit = 25,
            afterCursor = "cursor-1",
        )
        val booked = sampleTransaction("tx-booked", "BOOKED")
        val pending = sampleTransaction("tx-pending", "PENDING")

        coEvery { consentClient.validateConsent("consent-1", "tpp-1", "TRANSACTIONS_READ", "acc-1") } returns true
        coEvery {
            accountClient.getTransactions(
                accountId = "acc-1",
                dateFrom = LocalDate.of(2024, 1, 1),
                dateTo = LocalDate.of(2024, 1, 31),
                bookingStatus = BookingStatus.BOTH,
                limit = 25,
                afterCursor = "cursor-1",
            )
        } returns (listOf(booked, pending) to "next-cursor")

        val result = accountInformationService.getTransactions(query)

        assertThat(result).isEqualTo(
            TransactionPage(
                booked = listOf(booked),
                pending = listOf(pending),
                nextCursor = "next-cursor",
            ),
        )
        coVerify(exactly = 1) { consentClient.validateConsent("consent-1", "tpp-1", "TRANSACTIONS_READ", "acc-1") }
        coVerify(exactly = 1) {
            accountClient.getTransactions(
                accountId = "acc-1",
                dateFrom = LocalDate.of(2024, 1, 1),
                dateTo = LocalDate.of(2024, 1, 31),
                bookingStatus = BookingStatus.BOTH,
                limit = 25,
                afterCursor = "cursor-1",
            )
        }
    }

    @Test
    fun `createConsent clamps validUntil to 90 days`(): Unit = runBlocking {
        val access = ObAccess(
            accounts = listOf(sampleAccountRef("acc-iban")),
            balances = null,
            transactions = null,
            additionalInformation = null,
        )
        val request = ObConsentRequest(
            access = access,
            recurringIndicator = true,
            validUntil = fixedToday.plusDays(120),
            frequencyPerDay = 4,
        )
        val capturedValidUntil = slot<LocalDate>()

        coEvery {
            consentClient.createConsent(
                partyId = "",
                granteeId = "tpp-1",
                granteeName = "TPP One",
                scopes = setOf("ACCOUNTS_READ"),
                accountIbans = listOf("acc-iban"),
                validUntil = capture(capturedValidUntil),
                redirectUri = "https://example.test/callback",
                tppTransactionId = "tx-1",
                ipAddress = "127.0.0.1",
            )
        } returns "consent-123"

        val result = consentManagementService.createConsent(
            CreateConsentCommand(
                tppId = "tpp-1",
                tppName = "TPP One",
                request = request,
                redirectUri = "https://example.test/callback",
                tppTransactionId = "tx-1",
                ipAddress = "127.0.0.1",
            ),
        )

        assertThat(capturedValidUntil.captured).isEqualTo(fixedToday.plusDays(90))
        assertThat(result.validUntil).isEqualTo(fixedToday.plusDays(90))
        assertThat(result.consentId).isEqualTo("consent-123")
    }

    @Test
    fun `createConsent rejects a null JSON array element with IllegalArgumentException`(): Unit = runBlocking {
        // #7867: Jackson null-checks constructor parameters but not collection elements, so
        // `{"accounts": [null]}` arrives as a list holding a null. The guard must reject it
        // (IllegalArgumentException -> 400) before any dereference turns it into a 500.
        val request = ObConsentRequest(
            access = ObAccess(
                accounts = listOf(sampleAccountRef("acc-iban"), null),
                balances = null,
                transactions = null,
                additionalInformation = null,
            ),
            recurringIndicator = true,
            validUntil = fixedToday.plusDays(30),
            frequencyPerDay = 4,
        )

        var thrown: Throwable? = null
        try {
            consentManagementService.createConsent(
                CreateConsentCommand(
                    tppId = "tpp-1",
                    tppName = "TPP One",
                    request = request,
                    redirectUri = null,
                    tppTransactionId = null,
                    ipAddress = null,
                ),
            )
        } catch (e: IllegalArgumentException) {
            thrown = e
        }

        assertThat(thrown)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("access.accounts[1]")
        coVerify(exactly = 0) {
            consentClient.createConsent(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `createConsent returns consent response with correct links`(): Unit = runBlocking {
        val access = ObAccess(accounts = null, balances = null, transactions = null, additionalInformation = null)
        val request = ObConsentRequest(
            access = access,
            recurringIndicator = false,
            validUntil = fixedToday.plusDays(1),
            frequencyPerDay = 1,
        )

        coEvery {
            consentClient.createConsent(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns "consent-456"

        val result = consentManagementService.createConsent(
            CreateConsentCommand(
                tppId = "tpp-2",
                tppName = "TPP Two",
                request = request,
                redirectUri = null,
                tppTransactionId = null,
                ipAddress = null,
            ),
        )

        assertThat(result.links).isEqualTo(
            ObLinks(
                self = "/open-banking/v2/consents/consent-456",
                status = "/open-banking/v2/consents/consent-456/status",
                scaRedirect = "/open-banking/v2/consents/consent-456/authorisations",
            ),
        )
        assertThat(result.consentStatus).isEqualTo(ConsentStatusOb.RECEIVED)
    }

    @Test
    fun `deleteConsent delegates to consentClient`(): Unit = runBlocking {
        coEvery { consentClient.revokeConsent("consent-1", "tpp-1") } returns Unit

        consentManagementService.deleteConsent(DeleteConsentCommand("consent-1", "tpp-1"))

        coVerify(exactly = 1) { consentClient.revokeConsent("consent-1", "tpp-1") }
    }

    @Test
    fun `getConsentStatus maps ACTIVE to VALID`(): Unit = runBlocking {
        coEvery { consentClient.getConsentStatus("consent-1") } returns "ACTIVE"

        val result = consentManagementService.getConsentStatus(GetConsentQuery("consent-1", "tpp-1"))

        assertThat(result).isEqualTo(ConsentStatusOb.VALID)
    }

    @Test
    fun `getConsentStatus maps every upstream status to its Berlin equivalent`(): Unit = runBlocking {
        val expected = mapOf(
            "ACTIVE" to ConsentStatusOb.VALID,
            "PENDING_SCA" to ConsentStatusOb.RECEIVED,
            "REVOKED" to ConsentStatusOb.REVOKED_BY_PSU,
            "EXPIRED" to ConsentStatusOb.EXPIRED,
            "REJECTED" to ConsentStatusOb.REJECTED,
            "SOMETHING_UNKNOWN" to ConsentStatusOb.RECEIVED,
        )
        expected.forEach { (upstream, obStatus) ->
            coEvery { consentClient.getConsentStatus("consent-x") } returns upstream
            assertThat(consentManagementService.getConsentStatus(GetConsentQuery("consent-x", "tpp-1")))
                .isEqualTo(obStatus)
        }
    }

    @Test
    fun `getConsent maps status and defaults access to all-null`(): Unit = runBlocking {
        coEvery { consentClient.getConsent("consent-1") } returns
            ConsentSnapshot(consentId = "consent-1", partyId = "party-1", status = "REVOKED")

        val result = consentManagementService.getConsent(GetConsentQuery("consent-1", "tpp-1"))

        assertThat(result.consentId).isEqualTo("consent-1")
        assertThat(result.consentStatus).isEqualTo(ConsentStatusOb.REVOKED_BY_PSU)
        assertThat(result.access).isEqualTo(ObAccess(null, null, null, null))
        assertThat(result.recurringIndicator).isTrue()
        assertThat(result.frequencyPerDay).isEqualTo(4)
        assertThat(result.validUntil).isEqualTo(fixedToday.plusDays(90))
        assertThat(result.links).isEqualTo(ObLinks(self = "/open-banking/v2/consents/consent-1"))
    }

    @Test
    fun `createConsent aggregates scopes from accounts, balances, transactions and additional info`(): Unit =
        runBlocking {
            val access = ObAccess(
                accounts = listOf(sampleAccountRef("iban-a")),
                balances = listOf(sampleAccountRef("iban-b")),
                transactions = listOf(sampleAccountRef("iban-c")),
                additionalInformation = ObAdditionalInformation(
                    ownerName = null,
                    trustedBeneficiaries = null,
                    standingOrders = listOf(sampleAccountRef("iban-d")),
                    directDebits = listOf(sampleAccountRef("iban-e")),
                ),
            )
            val request = ObConsentRequest(
                access = access,
                recurringIndicator = true,
                validUntil = fixedToday.plusDays(30),
                frequencyPerDay = 4,
            )
            val capturedScopes = slot<Set<String>>()
            val capturedIbans = slot<List<String>>()

            coEvery {
                consentClient.createConsent(
                    partyId = any(),
                    granteeId = any(),
                    granteeName = any(),
                    scopes = capture(capturedScopes),
                    accountIbans = capture(capturedIbans),
                    validUntil = any(),
                    redirectUri = any(),
                    tppTransactionId = any(),
                    ipAddress = any(),
                )
            } returns "consent-789"

            consentManagementService.createConsent(
                CreateConsentCommand(
                    tppId = "tpp-1",
                    tppName = "TPP One",
                    request = request,
                    redirectUri = null,
                    tppTransactionId = null,
                    ipAddress = null,
                ),
            )

            assertThat(capturedScopes.captured).containsExactlyInAnyOrder(
                "ACCOUNTS_READ",
                "BALANCES_READ",
                "TRANSACTIONS_READ",
                "STANDING_ORDERS_READ",
                "DIRECT_DEBITS_READ",
            )
            // Only accounts/balances/transactions ibans are collected — additionalInformation
            // (standing orders / direct debits) contributes scopes but not IBAN scoping.
            assertThat(capturedIbans.captured).containsExactlyInAnyOrder("iban-a", "iban-b", "iban-c")
        }

    @Test
    fun `createConsent yields null ibans when access carries none`(): Unit = runBlocking {
        val access = ObAccess(accounts = null, balances = null, transactions = null, additionalInformation = null)
        val request = ObConsentRequest(
            access = access,
            recurringIndicator = false,
            validUntil = fixedToday.plusDays(10),
            frequencyPerDay = 1,
        )

        coEvery {
            consentClient.createConsent(
                partyId = any(),
                granteeId = any(),
                granteeName = any(),
                scopes = any(),
                accountIbans = null,
                validUntil = any(),
                redirectUri = any(),
                tppTransactionId = any(),
                ipAddress = any(),
            )
        } returns "consent-000"

        consentManagementService.createConsent(
            CreateConsentCommand(
                tppId = "tpp-1",
                tppName = "TPP One",
                request = request,
                redirectUri = null,
                tppTransactionId = null,
                ipAddress = null,
            ),
        )

        coVerify(exactly = 1) {
            consentClient.createConsent(
                partyId = any(),
                granteeId = any(),
                granteeName = any(),
                scopes = any(),
                accountIbans = null,
                validUntil = any(),
                redirectUri = any(),
                tppTransactionId = any(),
                ipAddress = any(),
            )
        }
    }

    @Test
    fun `initiatePayment validates SEPA consent scope and delegates to transactionClient`(): Unit = runBlocking {
        val payment = PaymentInitiation(
            endToEndIdentification = "e2e-1",
            debtorAccount = sampleAccountRef("CZ6508000000192000145399"),
            instructedAmount = ObAmount("CZK", BigDecimal("100.00")),
            creditorAccount = sampleAccountRef("CZ1234567890123456789012"),
            creditorName = "Acme",
            creditorAddress = null,
            remittanceInformationUnstructured = "invoice 123",
            requestedExecutionDate = null,
        )
        val command = InitiatePaymentCommand(
            tppId = "tpp-1",
            consentId = "consent-1",
            product = PaymentProduct.SEPA_CREDIT_TRANSFERS,
            payment = payment,
            idempotencyKey = "idem-1",
        )

        coEvery {
            consentClient.validateConsent("consent-1", "tpp-1", "PAYMENTS_INITIATE", "CZ6508000000192000145399")
        } returns true
        coEvery {
            transactionClient.initiatePayment(
                debtorIban = "CZ6508000000192000145399",
                creditorIban = "CZ1234567890123456789012",
                creditorName = "Acme",
                amount = BigDecimal("100.00"),
                currency = "CZK",
                endToEndId = "e2e-1",
                remittanceInfo = "invoice 123",
                idempotencyKey = "idem-1",
            )
        } returns "payment-1"

        val result = paymentInitiationService.initiatePayment(command)

        assertThat(result.paymentId).isEqualTo("payment-1")
        assertThat(result.transactionStatus).isEqualTo(PaymentStatus.RCVD)
        assertThat(result.scaStatus).isEqualTo("received")
        assertThat(result.links.self).isEqualTo("/open-banking/v2/payments/sepa_credit_transfers/payment-1")
        assertThat(result.links.status).isEqualTo("/open-banking/v2/payments/sepa_credit_transfers/payment-1/status")
    }

    @Test
    fun `initiatePayment throws ConsentUnauthorizedException when consent invalid`(): Unit = runBlocking {
        val payment = PaymentInitiation(
            endToEndIdentification = null,
            debtorAccount = sampleAccountRef("CZ6508000000192000145399"),
            instructedAmount = ObAmount("CZK", BigDecimal("50.00")),
            creditorAccount = sampleAccountRef("CZ1234567890123456789012"),
            creditorName = "Acme",
            creditorAddress = null,
            remittanceInformationUnstructured = null,
            requestedExecutionDate = null,
        )
        val command = InitiatePaymentCommand(
            tppId = "tpp-1",
            consentId = "consent-1",
            product = PaymentProduct.SEPA_CREDIT_TRANSFERS,
            payment = payment,
            idempotencyKey = "idem-2",
        )
        coEvery {
            consentClient.validateConsent("consent-1", "tpp-1", "PAYMENTS_INITIATE", "CZ6508000000192000145399")
        } returns false

        assertThatThrownBy { runBlocking { paymentInitiationService.initiatePayment(command) } }
            .isInstanceOf(ConsentUnauthorizedException::class.java)
    }

    @Test
    fun `initiatePayment requires debtor IBAN for SEPA payment`(): Unit = runBlocking {
        val payment = PaymentInitiation(
            endToEndIdentification = null,
            debtorAccount = sampleAccountRef(null),
            instructedAmount = ObAmount("CZK", BigDecimal("50.00")),
            creditorAccount = sampleAccountRef("CZ1234567890123456789012"),
            creditorName = "Acme",
            creditorAddress = null,
            remittanceInformationUnstructured = null,
            requestedExecutionDate = null,
        )
        val command = InitiatePaymentCommand(
            tppId = "tpp-1",
            consentId = "consent-1",
            product = PaymentProduct.SEPA_CREDIT_TRANSFERS,
            payment = payment,
            idempotencyKey = "idem-3",
        )
        coEvery { consentClient.validateConsent("consent-1", "tpp-1", "PAYMENTS_INITIATE", null) } returns true

        assertThatThrownBy { runBlocking { paymentInitiationService.initiatePayment(command) } }
            .isInstanceOf(InvalidPaymentProductException::class.java)
            .hasMessageContaining("Debtor IBAN required")
    }

    @Test
    fun `initiatePayment requires creditor IBAN for SEPA payment`(): Unit = runBlocking {
        val payment = PaymentInitiation(
            endToEndIdentification = null,
            debtorAccount = sampleAccountRef("CZ6508000000192000145399"),
            instructedAmount = ObAmount("CZK", BigDecimal("50.00")),
            creditorAccount = sampleAccountRef(null),
            creditorName = "Acme",
            creditorAddress = null,
            remittanceInformationUnstructured = null,
            requestedExecutionDate = null,
        )
        val command = InitiatePaymentCommand(
            tppId = "tpp-1",
            consentId = "consent-1",
            product = PaymentProduct.SEPA_CREDIT_TRANSFERS,
            payment = payment,
            idempotencyKey = "idem-4",
        )
        coEvery {
            consentClient.validateConsent("consent-1", "tpp-1", "PAYMENTS_INITIATE", "CZ6508000000192000145399")
        } returns true

        assertThatThrownBy { runBlocking { paymentInitiationService.initiatePayment(command) } }
            .isInstanceOf(InvalidPaymentProductException::class.java)
            .hasMessageContaining("Creditor IBAN required")
    }

    @Test
    fun `initiatePayment for DOMESTIC_CZ joins VS SS KS into remittance info and uses the DOMESTIC scope`(): Unit =
        runBlocking {
            val payment = DomesticCzPayment(
                endToEndIdentification = "e2e-cz",
                debtorAccount = sampleAccountRef("CZ6508000000192000145399"),
                instructedAmount = ObAmount("CZK", BigDecimal("250.00")),
                creditorAccount = sampleAccountRef("CZ1234567890123456789012"),
                creditorName = "Acme CZ",
                variableSymbol = "123",
                specificSymbol = "456",
                constantSymbol = "789",
                remittanceInformationUnstructured = null,
                requestedExecutionDate = null,
            )
            val command = InitiatePaymentCommand(
                tppId = "tpp-1",
                consentId = "consent-1",
                product = PaymentProduct.DOMESTIC_CZ,
                payment = payment,
                idempotencyKey = "idem-5",
            )

            coEvery {
                consentClient.validateConsent(
                    "consent-1",
                    "tpp-1",
                    "DOMESTIC_PAYMENT_INITIATE",
                    "CZ6508000000192000145399",
                )
            } returns true
            coEvery {
                transactionClient.initiatePayment(
                    debtorIban = "CZ6508000000192000145399",
                    creditorIban = "CZ1234567890123456789012",
                    creditorName = "Acme CZ",
                    amount = BigDecimal("250.00"),
                    currency = "CZK",
                    endToEndId = "e2e-cz",
                    remittanceInfo = "123/456/789",
                    idempotencyKey = "idem-5",
                )
            } returns "payment-cz-1"

            val result = paymentInitiationService.initiatePayment(command)

            assertThat(result.paymentId).isEqualTo("payment-cz-1")
            coVerify(exactly = 1) {
                transactionClient.initiatePayment(
                    debtorIban = "CZ6508000000192000145399",
                    creditorIban = "CZ1234567890123456789012",
                    creditorName = "Acme CZ",
                    amount = BigDecimal("250.00"),
                    currency = "CZK",
                    endToEndId = "e2e-cz",
                    remittanceInfo = "123/456/789",
                    idempotencyKey = "idem-5",
                )
            }
        }

    @Test
    fun `initiatePayment for SIPO zeroes the amount and targets the SIPO clearing account`(): Unit = runBlocking {
        val payment = SipoPayment(
            debtorAccount = sampleAccountRef("CZ6508000000192000145399"),
            sipoNumber = "1234567890",
            variableSymbol = "999",
            requestedExecutionDate = null,
        )
        val command = InitiatePaymentCommand(
            tppId = "tpp-1",
            consentId = "consent-1",
            product = PaymentProduct.SIPO,
            payment = payment,
            idempotencyKey = "idem-6",
        )

        coEvery {
            consentClient.validateConsent("consent-1", "tpp-1", "SIPO_PAYMENT_INITIATE", "CZ6508000000192000145399")
        } returns true
        coEvery {
            transactionClient.initiatePayment(
                debtorIban = "CZ6508000000192000145399",
                creditorIban = "CZ0000000000000000000000",
                creditorName = "SIPO",
                amount = BigDecimal.ZERO,
                currency = "CZK",
                endToEndId = "1234567890",
                remittanceInfo = "999",
                idempotencyKey = "idem-6",
            )
        } returns "payment-sipo-1"

        val result = paymentInitiationService.initiatePayment(command)

        assertThat(result.paymentId).isEqualTo("payment-sipo-1")
        assertThat(result.links.self).isEqualTo("/open-banking/v2/payments/sipo/payment-sipo-1")
    }

    @Test
    fun `getPaymentStatus delegates to transactionClient`(): Unit = runBlocking {
        coEvery { transactionClient.getPaymentStatus("payment-1") } returns PaymentStatus.ACSC

        val result = paymentInitiationService.getPaymentStatus(
            GetPaymentStatusQuery("payment-1", "tpp-1", PaymentProduct.SEPA_CREDIT_TRANSFERS),
        )

        assertThat(result).isEqualTo(PaymentStatus.ACSC)
        coVerify(exactly = 1) { transactionClient.getPaymentStatus("payment-1") }
    }

    private fun sampleAccount(id: String) = ObAccount(
        resourceId = id,
        iban = "CZ6508000000192000145399",
        currency = "CZK",
        ownerName = "Alice",
        name = "Main",
        product = "Current",
        cashAccountType = "CACC",
    )

    private fun sampleBalance() = ObBalance(
        balanceAmount = ObAmount(currency = "CZK", amount = BigDecimal("123.45")),
        balanceType = "interimAvailable",
        lastChangeDateTime = null,
        referenceDate = null,
    )

    private fun sampleAccountRef(iban: String?) = ObAccountRef(
        iban = iban,
        bban = null,
        pan = null,
        maskedPan = null,
        msisdn = null,
        currency = "CZK",
    )

    private fun sampleTransaction(id: String, bookingStatus: String) = ObTransaction(
        transactionId = id,
        entryReference = null,
        bookingDate = LocalDate.of(2024, 1, 10),
        valueDate = LocalDate.of(2024, 1, 10),
        transactionAmount = ObAmount(currency = "CZK", amount = BigDecimal("10.00")),
        creditorName = null,
        creditorAccount = null,
        debtorName = null,
        debtorAccount = null,
        remittanceInformationUnstructured = null,
        bankTransactionCode = null,
        bookingStatus = bookingStatus,
    )
}
