// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.client

import com.openbank.psd2.domain.model.BookingStatus
import com.openbank.psd2.domain.model.PaymentStatus
import com.openbank.psd2.domain.model.TppEventType
import com.openbank.psd2.domain.model.TppWebhookEvent
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Sandbox stub adapters (no real upstream — deterministic canned data). These back the `/v1` and
 * `/open-banking/v2` surfaces until a real account/consent/transaction-service integration lands;
 * still worth pinning down their (deterministic) contract since REST resources depend on it.
 */
class StubClientsTest {

    private val fixedClock = Clock.fixed(Instant.parse("2024-03-01T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `StubAccountServiceClient returns a single canned account for any party`(): Unit = runBlocking {
        val client = StubAccountServiceClient(fixedClock)

        val accounts = client.getAccountsByParty("party-1")

        assertThat(accounts).hasSize(1)
        assertThat(accounts[0].iban).isEqualTo("CZ6508000000192000145399")
        assertThat(accounts[0].currency).isEqualTo("CZK")
    }

    @Test
    fun `StubAccountServiceClient getAccountById echoes the requested id`(): Unit = runBlocking {
        val client = StubAccountServiceClient(fixedClock)

        val account = client.getAccountById("acc-42")

        assertThat(account).isNotNull
        assertThat(account!!.resourceId).isEqualTo("acc-42")
    }

    @Test
    fun `StubAccountServiceClient getBalances returns closingBooked and expected balances with the clock's date`(
    ): Unit = runBlocking {
        val client = StubAccountServiceClient(fixedClock)

        val balances = client.getBalances("acc-1")

        assertThat(balances).hasSize(2)
        assertThat(balances.map { it.balanceType }).containsExactly("closingBooked", "expected")
        balances.forEach {
            assertThat(it.balanceAmount.amount).isEqualByComparingTo(BigDecimal("12500.00"))
            assertThat(it.referenceDate).isEqualTo(java.time.LocalDate.of(2024, 3, 1))
            assertThat(it.lastChangeDateTime).isEqualTo(OffsetDateTime.now(fixedClock))
        }
    }

    @Test
    fun `StubAccountServiceClient getTransactions returns an empty page with no cursor`(): Unit = runBlocking {
        val client = StubAccountServiceClient(fixedClock)

        val (transactions, cursor) = client.getTransactions(
            "acc-1",
            null,
            null,
            BookingStatus.BOTH,
            10,
            null,
        )

        assertThat(transactions).isEmpty()
        assertThat(cursor).isNull()
    }

    @Test
    fun `StubConsentServiceClient getConsent echoes consentId as partyId and reports ACTIVE`(): Unit = runBlocking {
        val client = StubConsentServiceClient()

        val consent = client.getConsent("consent-1")

        assertThat(consent.consentId).isEqualTo("consent-1")
        assertThat(consent.partyId).isEqualTo("consent-1")
        assertThat(consent.status).isEqualTo("ACTIVE")
    }

    @Test
    fun `StubConsentServiceClient createConsent returns a fresh random id each call`(): Unit = runBlocking {
        val client = StubConsentServiceClient()

        val id1 = client.createConsent(
            partyId = "p",
            granteeId = "g",
            granteeName = "G",
            scopes = setOf("ACCOUNTS_READ"),
            accountIbans = null,
            validUntil = java.time.LocalDate.now(),
            redirectUri = null,
            tppTransactionId = null,
            ipAddress = null,
        )
        val id2 = client.createConsent(
            partyId = "p",
            granteeId = "g",
            granteeName = "G",
            scopes = setOf("ACCOUNTS_READ"),
            accountIbans = null,
            validUntil = java.time.LocalDate.now(),
            redirectUri = null,
            tppTransactionId = null,
            ipAddress = null,
        )

        assertThat(UUID.fromString(id1)).isNotNull()
        assertThat(id1).isNotEqualTo(id2)
    }

    @Test
    fun `StubConsentServiceClient getConsentStatus always reports ACTIVE`(): Unit = runBlocking {
        assertThat(StubConsentServiceClient().getConsentStatus("consent-1")).isEqualTo("ACTIVE")
    }

    @Test
    fun `StubConsentServiceClient validateConsent always allows`(): Unit = runBlocking {
        val client = StubConsentServiceClient()

        assertThat(client.validateConsent("consent-1", "tpp-1", "ACCOUNTS_READ", null)).isTrue()
    }

    @Test
    fun `StubConsentServiceClient revokeConsent completes without error`(): Unit = runBlocking {
        StubConsentServiceClient().revokeConsent("consent-1", "tpp-1")
    }

    @Test
    fun `StubTransactionServiceClient initiatePayment returns a fresh random id`(): Unit = runBlocking {
        val client = StubTransactionServiceClient()

        val id = client.initiatePayment(
            debtorIban = "CZ6508000000192000145399",
            creditorIban = "CZ1234567890123456789012",
            creditorName = "Acme",
            amount = BigDecimal("10.00"),
            currency = "CZK",
            endToEndId = "e2e-1",
            remittanceInfo = null,
            idempotencyKey = "idem-1",
        )

        assertThat(UUID.fromString(id)).isNotNull()
    }

    @Test
    fun `StubTransactionServiceClient getPaymentStatus always reports ACSC`(): Unit = runBlocking {
        assertThat(StubTransactionServiceClient().getPaymentStatus("payment-1")).isEqualTo(PaymentStatus.ACSC)
    }

    @Test
    fun `KafkaTppWebhookPublisher publish completes without error`(): Unit = runBlocking {
        val publisher = KafkaTppWebhookPublisher()
        val event = TppWebhookEvent(
            eventType = TppEventType.CONSENT_REVOKED,
            resourceId = "consent-1",
            resourceType = "CONSENT",
            timestamp = OffsetDateTime.now(fixedClock),
        )

        publisher.publish("tpp-1", event)
    }
}
