// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.interest.application.port.`in`.RemitWithholdingUseCase
import com.openbank.interest.infrastructure.client.InitiateTransactionRequest
import com.openbank.interest.infrastructure.client.TransactionServiceClient
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class WithholdingRemittanceSettlementConsumerTest {

    private val mapper = jacksonObjectMapper()
    private val transactionClient = mockk<TransactionServiceClient>()
    private val remitUseCase = mockk<RemitWithholdingUseCase>()
    private val sourceAccountId = "00000000-0000-0000-0000-0000000000aa"
    private val consumer = WithholdingRemittanceSettlementConsumer(
        mapper,
        remitUseCase,
        transactionClient,
        sourceAccountId,
    )

    private val remittanceId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")

    /**
     * Payload exactly as `WithholdingRemittanceService.remittedEvent` writes it: NO `eventType`
     * field (the type travels only as the `ce-type` header, ADR-0050 N3) and BigDecimal fields
     * serialized as JSON *strings* — the encoding that made `decimalValue()` return zero.
     */
    private fun remittedPayload(
        totalTaxAmount: String = "\"1234.00\"",
        itemCount: Int = 12,
        currency: String = "CZK",
    ): String = """{"schemaVersion":1,""" +
        """"remittanceId":"$remittanceId","periodYear":2026,"periodMonth":6,""" +
        """"authority":"CZ_FU","currency":"$currency",""" +
        """"totalTaxAmount":$totalTaxAmount,"itemCount":$itemCount,""" +
        """"dueDate":"2026-07-31","status":"PENDING"}"""

    /**
     * Builds the record the way `KafkaInterestOutboxEventPublisher` produces it: key = aggregate
     * id (N2), `ce-id`/`idempotency-key`/`ce-type` headers from [OutboxKafkaHeaders] (N3), raw
     * outbox payload as the value.
     */
    private fun record(
        payload: String = remittedPayload(),
        eventType: String? = "interest.withholding.remitted.v1",
    ): ConsumerRecord<String, String> {
        val record = ConsumerRecord("openbank.interest.accrual.event", 0, 0L, remittanceId.toString(), payload)
        val eventId = UUID.randomUUID().toString()
        record.headers().add(OutboxKafkaHeaders.HEADER_EVENT_ID, eventId.toByteArray())
        record.headers().add(OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY, eventId.toByteArray())
        if (eventType != null) {
            record.headers().add(OutboxKafkaHeaders.HEADER_EVENT_TYPE, eventType.toByteArray())
        }
        return record
    }

    @Test
    fun `books the remittance debit and settles the batch on a 2xx`(): Unit = runBlocking {
        val req = slot<InitiateTransactionRequest>()
        every { transactionClient.initiateTransaction(capture(req)) } returns
            Uni.createFrom().item(Response.status(201).build())
        every { remitUseCase.settle(remittanceId) } returns Uni.createFrom().item(Unit)

        consumer.consume(record())

        assertThat(req.captured.idempotencyKey).isEqualTo("interest-withholding-$remittanceId")
        assertThat(req.captured.type).isEqualTo("DEBIT")
        assertThat(req.captured.sourceAccountId).isEqualTo(UUID.fromString(sourceAccountId))
        // The string-encoded amount must decode to the real value, not decimalValue()'s ZERO.
        assertThat(req.captured.amount).isEqualByComparingTo("1234.00")
        assertThat(req.captured.currencyCode).isEqualTo("CZK")
        assertThat(req.captured.rail).isEqualTo("DOMESTIC")
        assertThat(req.captured.instructionType).isEqualTo("ONE_OFF")
        verify(exactly = 1) { remitUseCase.settle(remittanceId) }
    }

    @Test
    fun `a numeric totalTaxAmount encoding is accepted too`(): Unit = runBlocking {
        val req = slot<InitiateTransactionRequest>()
        every { transactionClient.initiateTransaction(capture(req)) } returns
            Uni.createFrom().item(Response.status(201).build())
        every { remitUseCase.settle(remittanceId) } returns Uni.createFrom().item(Unit)

        consumer.consume(record(payload = remittedPayload(totalTaxAmount = "1234.00")))

        assertThat(req.captured.amount).isEqualByComparingTo("1234.00")
        verify(exactly = 1) { remitUseCase.settle(remittanceId) }
    }

    @Test
    fun `a 409 from transaction-service still settles the batch (idempotent success)`(): Unit = runBlocking {
        every { transactionClient.initiateTransaction(any()) } returns
            Uni.createFrom().item(Response.status(409).build())
        every { remitUseCase.settle(remittanceId) } returns Uni.createFrom().item(Unit)

        consumer.consume(record())

        verify(exactly = 1) { remitUseCase.settle(remittanceId) }
    }

    /**
     * The contract this test asserted before #5698 was "does not throw" — i.e. a refused booking was
     * ACKED, so a due tax remittance silently never moved and nothing downstream could tell. It now
     * asserts the opposite: the failure is retried [EventRetry.DEFAULT_MAX_ATTEMPTS] times and then
     * rethrown for the connector to dead-letter. Both halves matter — the attempt count is what
     * distinguishes a real retry from a single call that happens to throw.
     */
    @Test
    fun `a non-2xx-non-409 response is retried and rethrown, never settled or acked`(): Unit = runBlocking {
        every { transactionClient.initiateTransaction(any()) } returns
            Uni.createFrom().item(Response.status(500).build())

        assertThatThrownBy { runBlocking { consumer.consume(record()) } }
            .isInstanceOf(WithholdingRemittanceBookingFailedException::class.java)
            .hasMessageContaining("did not move money")

        verify(exactly = EventRetry.DEFAULT_MAX_ATTEMPTS) { transactionClient.initiateTransaction(any()) }
        verify(exactly = 0) { remitUseCase.settle(any()) }
    }

    @Test
    fun `an accrual event with a different ce-type header is ignored`(): Unit = runBlocking {
        consumer.consume(record(eventType = "interest.withholding.recorded.v1"))

        verify(exactly = 0) { transactionClient.initiateTransaction(any()) }
        verify(exactly = 0) { remitUseCase.settle(any()) }
    }

    @Test
    fun `a record without a ce-type header is ignored`(): Unit = runBlocking {
        consumer.consume(record(eventType = null))

        verify(exactly = 0) { transactionClient.initiateTransaction(any()) }
        verify(exactly = 0) { remitUseCase.settle(any()) }
    }

    /**
     * The reachable sub-7-CZK case, spelled out because the previous version of this test asserted
     * the opposite and was wrong: tax is assessed in whole CZK (RoundingMode.DOWN), so a real
     * `WITHHELD` row on gross 6.00 CZK carries `taxAmount = 0` and is still remittable. Such a
     * batch owes nothing but MUST reach a terminal state — refusing it left it PENDING forever with
     * its rows already REMITTED and no re-drive endpoint.
     */
    @Test
    fun `a zero total over a non-empty batch settles without touching the rail`(): Unit = runBlocking {
        every { remitUseCase.settle(remittanceId) } returns Uni.createFrom().item(Unit)

        consumer.consume(record(payload = remittedPayload(totalTaxAmount = "\"0\"", itemCount = 12)))

        verify(exactly = 0) { transactionClient.initiateTransaction(any()) }
        verify(exactly = 1) { remitUseCase.settle(remittanceId) }
    }

    @Test
    fun `a negative amount is refused - no policy path can produce one`(): Unit = runBlocking {
        consumer.consume(record(payload = remittedPayload(totalTaxAmount = "\"-1\"", itemCount = 3)))

        verify(exactly = 0) { transactionClient.initiateTransaction(any()) }
        verify(exactly = 0) { remitUseCase.settle(any()) }
    }

    @Test
    fun `a nil batch (zero amount, zero items) settles without touching the rail`(): Unit = runBlocking {
        every { remitUseCase.settle(remittanceId) } returns Uni.createFrom().item(Unit)

        consumer.consume(record(payload = remittedPayload(totalTaxAmount = "\"0\"", itemCount = 0)))

        verify(exactly = 0) { transactionClient.initiateTransaction(any()) }
        verify(exactly = 1) { remitUseCase.settle(remittanceId) }
    }

    @Test
    fun `a malformed payload is swallowed, not thrown`(): Unit = runBlocking {
        consumer.consume(record(payload = "{bad-json"))

        verify(exactly = 0) { transactionClient.initiateTransaction(any()) }
    }
}
