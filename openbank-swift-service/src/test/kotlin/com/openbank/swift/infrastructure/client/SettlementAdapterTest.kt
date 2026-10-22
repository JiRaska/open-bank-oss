// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.client

import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.clients.transaction.model.InitiateTransactionRequest
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class SettlementAdapterTest {

    private val client = mockk<TransactionServiceClient>()
    private val adapter = SettlementAdapter(client)

    private fun created(id: UUID, status: Int = 201): Response =
        Response.status(status).entity(TransactionResponse(id = id, status = "COMPLETED")).build()

    /** The booking transaction-service makes for [request]: the only response that may settle. */
    private fun matching(request: InitiateTransactionRequest, id: UUID = UUID.randomUUID()) = TransactionResponse(
        id = id,
        status = "COMPLETED",
        type = request.type,
        sourceAccountId = request.sourceAccountId,
        amount = request.amount,
        currencyCode = request.currencyCode,
        rail = request.rail,
        instructionType = request.instructionType,
        // A later business day: valueDate is deliberately not part of the binding.
        valueDate = request.valueDate.plusDays(1),
    )

    /** Answers with [status] and the body [body] derives from the request actually sent. */
    private fun answer(status: Int = 201, body: (InitiateTransactionRequest) -> TransactionResponse) {
        every { client.initiateTransaction(any()) } answers {
            Uni.createFrom().item(Response.status(status).entity(body(firstArg())).build())
        }
    }

    @Test
    fun `settle returns settled=true with transactionId on HTTP 201`(): Unit = runBlocking {
        val txId = UUID.randomUUID()
        answer { matching(it, txId) }

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
    fun `a matching COMPLETED 200 or 202 is not the provider's answer to a create and never settles`(): Unit =
        runBlocking {
            listOf(200, 202).forEach { code ->
                answer(code) { matching(it) }
                val outcome = adapter.settle(message())
                assertThat(outcome.settled).describedAs("HTTP %d", code).isFalse()
                assertThat(outcome.transactionId).isNull()
            }
        }

    @Test
    fun `a PENDING 202 has unproven completion and is unavailable`() {
        answer(202) { matching(it).copy(status = "PENDING") }

        assertThatThrownBy { runBlocking { adapter.settle(message()) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
            .hasCauseInstanceOf(TransactionCompletionUncertainException::class.java)
    }

    @ParameterizedTest(name = "mismatched {0}")
    @CsvSource("type", "account", "amount", "currency", "rail", "instructionType")
    fun `a COMPLETED 201 for a different booking never settles the SWIFT message`(field: String): Unit = runBlocking {
        answer { request ->
            val tx = matching(request)
            when (field) {
                "type" -> tx.copy(type = "CREDIT")
                "account" -> tx.copy(sourceAccountId = UUID.randomUUID())
                "amount" -> tx.copy(amount = request.amount.add(BigDecimal.ONE))
                "currency" -> tx.copy(currencyCode = "USD")
                "rail" -> tx.copy(rail = "SEPA_CT")
                "instructionType" -> tx.copy(instructionType = "STANDING_ORDER")
                else -> error(field)
            }
        }

        val outcome = adapter.settle(message())

        assertThat(outcome.settled).isFalse()
        assertThat(outcome.transactionId).isNull()
    }

    @Test
    fun `a matching COMPLETED 201 with no transaction id never settles`(): Unit = runBlocking {
        answer { matching(it).copy(id = null) }

        assertThat(adapter.settle(message()).settled).isFalse()
    }

    @Test
    fun `a partial HTTP 206 response cannot confirm completed settlement`() {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().item(created(UUID.randomUUID(), status = 206))

        val outcome = runBlocking { adapter.settle(message()) }

        assertThat(outcome.settled).isFalse()
        assertThat(outcome.transactionId).isNull()
    }

    @Test
    fun `a 204 has unproven completion and is unavailable`() {
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(Response.status(204).build())

        assertThatThrownBy { runBlocking { adapter.settle(message()) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @ParameterizedTest
    @CsvSource("FAILED", "PENDING", "PROCESSING", "INITIATED", "REVERSED", "UNKNOWN")
    fun `a readable noncompleted transaction never settles the SWIFT message`(status: String) {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().item(
                Response.status(201).entity(TransactionResponse(id = UUID.randomUUID(), status = status)).build(),
            )

        assertThatThrownBy { runBlocking { adapter.settle(message()) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `a 201 without a response body has unproven completion`() {
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(Response.status(201).build())

        assertThatThrownBy { runBlocking { adapter.settle(message()) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `a 2xx with an unreadable body never proves settlement`() {
        val response = mockk<Response>(relaxed = true)
        every { response.status } returns 201
        every { response.hasEntity() } returns true
        every { response.readEntity(TransactionResponse::class.java) } throws IllegalStateException("garbled")
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(response)

        assertThatThrownBy { runBlocking { adapter.settle(message()) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `a transaction without a completion status never settles`() {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().item(Response.status(201).entity(TransactionResponse(id = UUID.randomUUID())).build())

        assertThatThrownBy { runBlocking { adapter.settle(message()) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `a later completed response reuses the original settlement idempotency key`(): Unit = runBlocking {
        val msg = message()
        every { client.initiateTransaction(match { it.idempotencyKey == "swift-settlement-${msg.id}" }) } returnsMany
            listOf(
                Uni.createFrom().item(
                    Response.status(201)
                        .entity(TransactionResponse(id = UUID.randomUUID(), status = "PENDING"))
                        .build(),
                ),
            ) andThenAnswer { Uni.createFrom().item(Response.status(201).entity(matching(firstArg())).build()) }

        assertThatThrownBy { runBlocking { adapter.settle(msg) } }
            .isInstanceOf(SettlementUnavailableException::class.java)
        assertThat(adapter.settle(msg).settled).isTrue()
        verify(exactly = 2) { client.initiateTransaction(match { it.idempotencyKey == "swift-settlement-${msg.id}" }) }
    }

    @Test
    fun `a 422 refusal leaves the message SENT`(): Unit = runBlocking {
        every { client.initiateTransaction(any()) } returns Uni.createFrom().failure(WebApplicationException(422))

        assertThat(adapter.settle(message()).settled).isFalse()
    }

    @Test
    fun `a 503 is unavailable and does not decide settlement`() {
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
        every { client.initiateTransaction(match { it.valueDate == LocalDate.parse("2026-06-22") }) } answers
            { Uni.createFrom().item(Response.status(201).entity(matching(firstArg())).build()) }

        assertThat(adapter.settle(message()).settled).isTrue()
    }

    @Test
    fun `settle uses idempotency key prefixed with swift-settlement`(): Unit = runBlocking {
        val msgId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        every { client.initiateTransaction(match { it.idempotencyKey == "swift-settlement-$msgId" }) } answers
            { Uni.createFrom().item(Response.status(201).entity(matching(firstArg())).build()) }

        val outcome = adapter.settle(message(id = msgId))

        assertThat(outcome.settled).isTrue()
    }

    @Test
    fun `settle converts amountMinorUnits to major units with two decimal places`(): Unit = runBlocking {
        every {
            client.initiateTransaction(
                match { it.amount.toPlainString() == "100.00" && it.currencyCode == "EUR" },
            )
        } answers { Uni.createFrom().item(Response.status(201).entity(matching(firstArg())).build()) }

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
        } answers { Uni.createFrom().item(Response.status(201).entity(matching(firstArg())).build()) }

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
