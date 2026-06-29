// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.SettlementUnavailableException
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.quarkus.oidc.client.OidcClient
import io.quarkus.oidc.client.Tokens
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SettlementAdapterTest {

    private val client: TransactionServiceClient = mockk()
    private val oidcClient: OidcClient = mockk()
    private val oidcClientInstance: Instance<OidcClient> = mockk()
    private val accountLookup: AccountLookupPort = mockk()

    private fun adapter(): SettlementAdapter =
        SettlementAdapter(client, oidcClientInstance, accountLookup, Clock.systemUTC()).also { it.self = it }

    @BeforeEach
    fun setUp() {
        val tokens = mockk<Tokens>()
        every { tokens.accessToken } returns "test-token"
        every { oidcClient.tokens } returns Uni.createFrom().item(tokens)
        every { oidcClientInstance.get() } returns oidcClient
        // Default: no internal creditor resolved → debit-only booking (existing behaviour).
        coEvery { accountLookup.findAccountIdByIban(any()) } returns null
    }

    @Test
    fun `settle returns settled=true and transactionId on HTTP 201`(): Unit = runBlocking {
        val txId = UUID.randomUUID()
        val response = mockk<Response>()
        every { response.status } returns 201
        every { response.readEntity(String::class.java) } returns """{"id":"$txId","status":"BOOKED"}"""
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        val outcome = adapter().settle(payment())

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isEqualTo(txId)
        verify(exactly = 1) { client.initiateTransaction("Bearer test-token", any()) }
    }

    @Test
    fun `settle returns settled=true with null transactionId on HTTP 409 (idempotent)`(): Unit = runBlocking {
        val response = mockk<Response>()
        every { response.status } returns 409
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        val outcome = adapter().settle(payment())

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isNull()
    }

    @Test
    fun `settle throws SettlementUnavailableException on HTTP 500`(): Unit = runBlocking {
        val response = mockk<Response>()
        every { response.status } returns 500
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        assertThatThrownBy { runBlocking { adapter().settle(payment()) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
            .hasMessageContaining("HTTP 500")
    }

    @Test
    fun `settle throws SettlementUnavailableException on connection failure`() {
        every { client.initiateTransaction(any(), any()) } returns
            Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThatThrownBy { runBlocking { adapter().settle(payment()) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `settle sends correct idempotencyKey and rail`(): Unit = runBlocking {
        val p = payment()
        val response = mockk<Response>()
        every { response.status } returns 201
        every { response.readEntity(String::class.java) } returns """{"id":"${UUID.randomUUID()}"}"""
        val requestSlot = slot<InitiateSettlementRequest>()
        every { client.initiateTransaction(any(), capture(requestSlot)) } returns
            Uni.createFrom().item(response)

        adapter().settle(p)

        val req = requestSlot.captured
        assertThat(req.idempotencyKey).isEqualTo("domestic-settlement-${p.id}")
        assertThat(req.rail).isEqualTo("DOMESTIC")
        assertThat(req.type).isEqualTo("DEBIT")
        assertThat(req.sourceAccountId).isEqualTo(p.debtorAccountId)
        assertThat(req.amount).isEqualByComparingTo(p.amount)
        assertThat(req.currencyCode).isEqualTo(p.currency)
    }

    @Test
    fun `settle resolves the payee and sends targetAccountId for an internal transfer`(): Unit = runBlocking {
        val p = payment() // INTERNAL_CLIENT, creditor 9876543210 / bank 0100
        val creditorId = UUID.randomUUID()
        val ibanSlot = slot<String>()
        coEvery { accountLookup.findAccountIdByIban(capture(ibanSlot)) } returns creditorId
        val response = mockk<Response>()
        every { response.status } returns 201
        every { response.readEntity(String::class.java) } returns """{"id":"${UUID.randomUUID()}"}"""
        val reqSlot = slot<InitiateSettlementRequest>()
        every { client.initiateTransaction(any(), capture(reqSlot)) } returns Uni.createFrom().item(response)

        adapter().settle(p)

        // The constructed Czech IBAN: CZ + check + bank(0100) + 16-digit account(0000009876543210).
        assertThat(ibanSlot.captured).hasSize(24)
        assertThat(ibanSlot.captured).startsWith("CZ")
        assertThat(ibanSlot.captured.substring(4)).isEqualTo("01000000009876543210")
        // The payee is credited: the booking carries the resolved target account.
        assertThat(reqSlot.captured.targetAccountId).isEqualTo(creditorId)
    }

    @Test
    fun `settle books debit-only (no targetAccountId) for an external transfer`(): Unit = runBlocking {
        val p = payment().copy(transferScope = DomesticTransferScope.EXTERNAL)
        val response = mockk<Response>()
        every { response.status } returns 201
        every { response.readEntity(String::class.java) } returns """{"id":"${UUID.randomUUID()}"}"""
        val reqSlot = slot<InitiateSettlementRequest>()
        every { client.initiateTransaction(any(), capture(reqSlot)) } returns Uni.createFrom().item(response)

        adapter().settle(p)

        assertThat(reqSlot.captured.targetAccountId).isNull()
        coVerify(exactly = 0) { accountLookup.findAccountIdByIban(any()) }
    }

    private fun payment() = DomesticPayment(
        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        idempotencyKey = "settle-test",
        status = DomesticPaymentStatus.SENT_TO_CLEARING,
        debtorAccountId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Alice",
        creditorAccountNumber = "9876543210",
        creditorBankCode = "0100",
        creditorName = "Bob",
        amount = BigDecimal("500.00"),
        currency = "CZK",
        variableSymbol = "12345",
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = "Test payment",
        priority = DomesticPaymentPriority.STANDARD,
        transferScope = DomesticTransferScope.INTERNAL_CLIENT,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOM-E2E-001",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        settledAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
