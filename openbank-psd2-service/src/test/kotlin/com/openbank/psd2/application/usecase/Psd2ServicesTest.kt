// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.application.usecase

import com.openbank.psd2.application.port.`in`.CreateConsentCommand
import com.openbank.psd2.application.port.`in`.DeleteConsentCommand
import com.openbank.psd2.application.port.`in`.GetAccountsQuery
import com.openbank.psd2.application.port.`in`.GetBalancesQuery
import com.openbank.psd2.application.port.`in`.GetConsentQuery
import com.openbank.psd2.application.port.`in`.GetTransactionsQuery
import com.openbank.psd2.application.port.`in`.TransactionPage
import com.openbank.psd2.application.port.out.AccountServiceClient
import com.openbank.psd2.application.port.out.ConsentServiceClient
import com.openbank.psd2.application.port.out.ConsentSnapshot
import com.openbank.psd2.domain.model.BookingStatus
import com.openbank.psd2.domain.model.ConsentStatusOb
import com.openbank.psd2.domain.model.ObAccess
import com.openbank.psd2.domain.model.ObAccount
import com.openbank.psd2.domain.model.ObAccountRef
import com.openbank.psd2.domain.model.ObAmount
import com.openbank.psd2.domain.model.ObBalance
import com.openbank.psd2.domain.model.ObConsentRequest
import com.openbank.psd2.domain.model.ObLinks
import com.openbank.psd2.domain.model.ObTransaction
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

    private val accountInformationService = AccountInformationService(accountClient, consentClient)
    private val consentManagementService = ConsentManagementService(consentClient, fixedClock)

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

    private fun sampleAccountRef(iban: String) = ObAccountRef(
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
