// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.interest.application.port.`in`.RemitWithholdingUseCase
import com.openbank.interest.infrastructure.client.InitiateTransactionRequest
import com.openbank.interest.infrastructure.client.TransactionServiceClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
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

    private fun remittedEvent(
        eventType: String = "interest.withholding.remitted.v1",
        totalTaxAmount: String = "1234.00",
        currency: String = "CZK",
    ): String = mapper.writeValueAsString(
        mapOf(
            "schemaVersion" to 1,
            "eventType" to eventType,
            "remittanceId" to remittanceId,
            "periodYear" to 2026,
            "periodMonth" to 6,
            "authority" to "CZ_FU",
            "currency" to currency,
            "totalTaxAmount" to totalTaxAmount.toBigDecimal(),
            "itemCount" to 12,
            "dueDate" to "2026-07-31",
            "status" to "PENDING",
        ),
    )

    @Test
    fun `books the remittance debit and settles the batch on a 2xx`(): Unit = runBlocking {
        val req = slot<InitiateTransactionRequest>()
        every { transactionClient.initiateTransaction(capture(req)) } returns
            Uni.createFrom().item(Response.status(201).build())
        every { remitUseCase.settle(remittanceId) } returns Uni.createFrom().item(Unit)

        consumer.consume(remittedEvent())

        assertThat(req.captured.idempotencyKey).isEqualTo("interest-withholding-$remittanceId")
        assertThat(req.captured.type).isEqualTo("DEBIT")
        assertThat(req.captured.sourceAccountId).isEqualTo(UUID.fromString(sourceAccountId))
        assertThat(req.captured.amount).isEqualByComparingTo("1234.00")
        assertThat(req.captured.currencyCode).isEqualTo("CZK")
        assertThat(req.captured.rail).isEqualTo("DOMESTIC")
        assertThat(req.captured.instructionType).isEqualTo("ONE_OFF")
        verify(exactly = 1) { remitUseCase.settle(remittanceId) }
    }

    @Test
    fun `a 409 from transaction-service still settles the batch (idempotent success)`(): Unit = runBlocking {
        every { transactionClient.initiateTransaction(any()) } returns
            Uni.createFrom().item(Response.status(409).build())
        every { remitUseCase.settle(remittanceId) } returns Uni.createFrom().item(Unit)

        consumer.consume(remittedEvent())

        verify(exactly = 1) { remitUseCase.settle(remittanceId) }
    }

    @Test
    fun `a non-2xx-non-409 response does not settle the batch and does not throw`(): Unit = runBlocking {
        every { transactionClient.initiateTransaction(any()) } returns
            Uni.createFrom().item(Response.status(500).build())

        consumer.consume(remittedEvent())

        verify(exactly = 1) { transactionClient.initiateTransaction(any()) }
        verify(exactly = 0) { remitUseCase.settle(any()) }
    }

    @Test
    fun `an accrual event that is not a remitted event is ignored`(): Unit = runBlocking {
        consumer.consume(remittedEvent(eventType = "interest.accrual.recorded.v1"))

        verify(exactly = 0) { transactionClient.initiateTransaction(any()) }
        verify(exactly = 0) { remitUseCase.settle(any()) }
    }

    @Test
    fun `a malformed payload is swallowed, not thrown`(): Unit = runBlocking {
        consumer.consume("{bad-json")

        verify(exactly = 0) { transactionClient.initiateTransaction(any()) }
    }
}
