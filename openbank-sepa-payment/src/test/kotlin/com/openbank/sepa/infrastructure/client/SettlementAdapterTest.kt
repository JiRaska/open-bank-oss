// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.sepa.application.port.out.SettlementUnavailableException
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import io.mockk.every
import io.mockk.mockk
import io.quarkus.oidc.client.OidcClient
import io.quarkus.oidc.client.Tokens
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SettlementAdapterTest {
    private val client = mockk<TransactionServiceClient>()
    private val oidcClient = mockk<OidcClient> {
        every { tokens } returns Uni.createFrom().item(mockk<Tokens> { every { accessToken } returns "test-token" })
    }
    private val oidcClientInstance = mockk<Instance<OidcClient>> { every { get() } returns oidcClient }
    private val adapter = SettlementAdapter(client, oidcClientInstance, Clock.systemUTC())
        .also { it.self = it }

    private val txId = UUID.randomUUID()

    private fun payment() = SepaPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-1",
        type = SepaPaymentType.SCT,
        status = SepaPaymentStatus.PROCESSING,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Debtor",
        creditorIban = "FR1420041010050500013M02606",
        creditorName = "Bob Creditor",
        creditorBic = "BNPAFRPPXXX",
        amount = BigDecimal("12.34"),
        currency = "EUR",
        remittanceInfo = "Invoice 1",
        endToEndId = "E2E-0001",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = Instant.now(),
        completedAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun createdResponse(id: UUID): Response {
        val resp = mockk<Response>()
        every { resp.status } returns Response.Status.CREATED.statusCode
        every { resp.readEntity(Map::class.java) } returns mapOf("id" to id.toString())
        return resp
    }

    @Test
    fun `successful 201 returns settled=true with transactionId`(): Unit = runBlocking {
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(createdResponse(txId))
        val outcome = adapter.settle(payment())
        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isEqualTo(txId)
    }

    @Test
    fun `HTTP 409 conflict returns settled=true with null transactionId`(): Unit = runBlocking {
        val resp = mockk<Response>()
        every { resp.status } returns Response.Status.CONFLICT.statusCode
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(resp)
        val outcome = adapter.settle(payment())
        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isNull()
    }

    @Test
    fun `HTTP 500 from transaction-service throws SettlementUnavailableException`() {
        val resp = mockk<Response>()
        every { resp.status } returns Response.Status.INTERNAL_SERVER_ERROR.statusCode
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(resp)
        assertThatThrownBy { runBlocking { adapter.settle(payment()) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `connection failure throws SettlementUnavailableException`() {
        every { client.initiateTransaction(any(), any()) } returns
            Uni.createFrom().failure(RuntimeException("connection refused"))
        assertThatThrownBy { runBlocking { adapter.settle(payment()) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }
}
