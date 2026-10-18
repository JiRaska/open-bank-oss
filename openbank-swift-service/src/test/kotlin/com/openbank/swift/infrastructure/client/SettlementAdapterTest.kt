// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.client

import com.openbank.clients.transaction.model.TransactionResponse
import com.openbank.swift.application.port.out.SettlementUnavailableException
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class SettlementAdapterTest {

    private val client = mockk<TransactionServiceClient>()
    private val adapter = SettlementAdapter(client)

    private fun created(id: UUID, status: Int = 201): Response =
        Response.status(status).entity(TransactionResponse(id = id, status = "COMPLETED")).build()

    @Test
    fun `settle returns settled=true with transactionId on HTTP 201`(): Unit = runBlocking {
        val txId = UUID.randomUUID()
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().item(created(txId))

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
    fun `settle returns settled=false when transaction-service refuses with an HTTP error`(): Unit = runBlocking {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().failure(WebApplicationException(409))

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
    fun `coroutine cancellation propagates and is not turned into SettlementUnavailableException`() {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().failure(CancellationException("cancelled"))

        assertThatThrownBy { runBlocking { adapter.settle(message()) } }
            .isInstanceOf(CancellationException::class.java)
            .isNotInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `settle accepts 200 and 202 as a booking`(): Unit = runBlocking {
        listOf(200, 202).forEach { code ->
            every { client.initiateTransaction(any()) } returns Uni.createFrom().item(created(UUID.randomUUID(), code))
            assertThat(adapter.settle(message()).settled).describedAs("HTTP %d", code).isTrue()
        }
    }

    @Test
    fun `a 204 is NOT a booking - it carries none`(): Unit = runBlocking {
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(Response.status(204).build())

        val outcome = adapter.settle(message())

        assertThat(outcome.settled).isFalse()
        assertThat(outcome.transactionId).isNull()
    }

    @Test
    fun `a 2xx with an unreadable body is still a booking, just without an id`(): Unit = runBlocking {
        val response = mockk<Response>(relaxed = true)
        every { response.status } returns 201
        every { response.hasEntity() } returns true
        every { response.readEntity(TransactionResponse::class.java) } throws IllegalStateException("garbled")
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(response)

        val outcome = adapter.settle(message())

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isNull()
    }

    @Test
    fun `a 422 refusal leaves the message SENT`(): Unit = runBlocking {
        every { client.initiateTransaction(any()) } returns Uni.createFrom().failure(WebApplicationException(422))

        assertThat(adapter.settle(message()).settled).isFalse()
    }

    @Test
    fun `a 503 is not decided - it is retried via SettlementUnavailableException`() {
        every { client.initiateTransaction(any()) } returns Uni.createFrom().failure(WebApplicationException(503))

        assertThatThrownBy { runBlocking { adapter.settle(message()) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `an unparseable stored value date sends nothing and does not settle`(): Unit = runBlocking {
        val outcome = adapter.settle(message(valueDate = "2026-06-22"))

        assertThat(outcome.settled).isFalse()
        verify(exactly = 0) { client.initiateTransaction(any()) }
    }

    // transaction-service parses valueDate as ISO-8601; the MT103 value date arrives as YYYYMMDD. The
    // raw string used to go on the wire unchanged and could not be parsed on the other side.
    @Test
    fun `settle sends the MT103 YYYYMMDD value date as an ISO date`(): Unit = runBlocking {
        every { client.initiateTransaction(match { it.valueDate == LocalDate.parse("2026-06-22") }) } returns
            Uni.createFrom().item(created(UUID.randomUUID()))

        assertThat(adapter.settle(message()).settled).isTrue()
    }

    @Test
    fun `settle uses idempotency key prefixed with swift-settlement`(): Unit = runBlocking {
        val msgId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        every { client.initiateTransaction(match { it.idempotencyKey == "swift-settlement-$msgId" }) } returns
            Uni.createFrom().item(created(UUID.randomUUID()))

        val outcome = adapter.settle(message(id = msgId))

        assertThat(outcome.settled).isTrue()
    }

    @Test
    fun `settle converts amountMinorUnits to major units with two decimal places`(): Unit = runBlocking {
        every {
            client.initiateTransaction(
                match { it.amount.toPlainString() == "100.00" && it.currencyCode == "EUR" },
            )
        } returns Uni.createFrom().item(created(UUID.randomUUID()))

        val outcome = adapter.settle(message(amountMinorUnits = 10_000L))

        assertThat(outcome.settled).isTrue()
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource("10000, EUR, 100.00", "1000, JPY, 1000", "1500, KWD, 1.500")
    fun `settle books the amount at the currency's own minor unit`(
        minorUnits: Long,
        currency: String,
        booked: String,
    ): Unit = runBlocking {
        // #11604: main shifted by a fixed two places and booked 1000 yen as 10.00 JPY.
        every {
            client.initiateTransaction(match { it.amount.toPlainString() == booked && it.currencyCode == currency })
        } returns Uni.createFrom().item(created(UUID.randomUUID()))

        assertThat(adapter.settle(message(amountMinorUnits = minorUnits, currency = currency)).settled).isTrue()
    }

    private fun message(
        id: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555"),
        accountId: UUID? = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        amountMinorUnits: Long = 1_000_00L,
        currency: String = "EUR",
        valueDate: String = "20260622",
    ) = SwiftMessage(
        id = id,
        idempotencyKey = "test",
        messageType = SwiftMessageType.MT103,
        senderBic = "ABCDEFGH",
        receiverBic = "IJKLMNOP",
        transactionReference = "TRX-001",
        relatedReference = null,
        valueDate = valueDate,
        amount = SwiftMessage.moneyOfMinorUnits(amountMinorUnits, currency),
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
