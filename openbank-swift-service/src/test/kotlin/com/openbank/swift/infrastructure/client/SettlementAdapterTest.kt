// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.client

import com.openbank.swift.application.port.out.SettlementUnavailableException
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SettlementAdapterTest {

    private val client = mockk<TransactionServiceClient>()
    private val adapter = SettlementAdapter(client)

    @Test
    fun `settle returns settled=true with transactionId on HTTP 201`(): Unit = runBlocking {
        val txId = UUID.randomUUID()
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().item(
                Response.status(201).entity(mapOf("id" to txId.toString())).build(),
            )

        val outcome = adapter.settle(message())

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isEqualTo(txId)
    }

    @Test
    fun `settle returns settled=false without account UUID`(): Unit = runBlocking {
        val outcome = adapter.settle(message(accountId = null))

        assertThat(outcome.settled).isFalse()
        assertThat(outcome.transactionId).isNull()
    }

    @Test
    fun `settle returns settled=false on non-2xx response`(): Unit = runBlocking {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().item(Response.status(409).build())

        val outcome = adapter.settle(message())

        assertThat(outcome.settled).isFalse()
        assertThat(outcome.transactionId).isNull()
    }

    @Test
    fun `settle throws SettlementUnavailableException when client throws`() {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThatThrownBy { runBlocking { adapter.settle(message()) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
            .hasMessageContaining("transaction-service unreachable")
    }

    @Test
    fun `settle uses idempotency key prefixed with swift-settlement`(): Unit = runBlocking {
        val msgId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        every { client.initiateTransaction(match { it.idempotencyKey == "swift-settlement-$msgId" }) } returns
            Uni.createFrom().item(Response.status(201).entity(mapOf("id" to UUID.randomUUID().toString())).build())

        val outcome = adapter.settle(message(id = msgId))

        assertThat(outcome.settled).isTrue()
    }

    @Test
    fun `settle converts amountMinorUnits to major units with two decimal places`(): Unit = runBlocking {
        every {
            client.initiateTransaction(
                match { it.amount.toPlainString() == "100.00" && it.currencyCode == "EUR" },
            )
        } returns Uni.createFrom().item(
            Response.status(201).entity(mapOf("id" to UUID.randomUUID().toString())).build(),
        )

        val outcome = adapter.settle(message(amountMinorUnits = 10_000L))

        assertThat(outcome.settled).isTrue()
    }

    private fun message(
        id: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555"),
        accountId: UUID? = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        amountMinorUnits: Long = 1_000_00L,
    ) = SwiftMessage(
        id = id,
        idempotencyKey = "test",
        messageType = SwiftMessageType.MT103,
        senderBic = "ABCDEFGH",
        receiverBic = "IJKLMNOP",
        transactionReference = "TRX-001",
        relatedReference = null,
        valueDate = "20260622",
        currency = "EUR",
        amountMinorUnits = amountMinorUnits,
        orderingCustomerAccount = "DE89370400440532013000",
        orderingCustomerAccountId = accountId,
        orderingCustomerName = "Alice",
        beneficiaryAccount = "GB33BUKB20201555555555",
        beneficiaryName = "Bob",
        remittanceInfo = "Invoice 1",
        chargeCode = "SHA",
        priority = SwiftPriority.NORMAL,
        status = SwiftStatus.SENT,
        rawMt = "<pacs.008/>",
        ackReceivedAt = null,
        rejectionReason = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
