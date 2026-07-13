// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
import com.openbank.standingorder.infrastructure.client.CreateSepaPaymentRequest
import com.openbank.standingorder.infrastructure.client.SepaPaymentClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class StandingOrderDueConsumerTest {

    private val useCase = mockk<StandingOrderUseCase>()
    private val sepaClient = mockk<SepaPaymentClient>()
    private val mapper = jacksonObjectMapper()
    private val consumer = StandingOrderDueConsumer(useCase, mapper, sepaClient)

    private val orderId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")

    private fun dueEvent(
        paymentType: String = "SEPA_CREDIT",
        debtorIban: String? = "DE89370400440532013001",
        debtorName: String? = "Debtor",
        amountMinorUnits: Long = 220000L,
        currency: String = "CZK",
    ): String {
        val payload = mutableMapOf<String, Any?>(
            "orderId" to orderId,
            "paymentType" to paymentType,
            "debitAccountId" to UUID.fromString("00000000-0000-0000-0000-0000000000d2"),
            "debtorIban" to debtorIban,
            "debtorName" to debtorName,
            "creditorIban" to "DE89370400440532013000",
            "creditorName" to "Creditor",
            "creditorBic" to "DEUTDEFF",
            "amountMinorUnits" to amountMinorUnits,
            "currency" to currency,
            "remittanceInfo" to "Rent",
            "idempotencyKey" to "so-exec-$orderId-2026-07-13",
            "executionDate" to "2026-07-13",
        )
        return mapper.writeValueAsString(payload)
    }

    @Test
    fun `SEPA_CREDIT due event initiates a SEPA transfer and confirms execution on 2xx`() = runBlocking {
        val req = slot<CreateSepaPaymentRequest>()
        val key = slot<String>()
        every { sepaClient.createPayment(capture(key), capture(req)) } returns
            Uni.createFrom().item(Response.status(201).build())
        coEvery { useCase.confirmExecution(orderId) } returns mockk(relaxed = true)

        consumer.consume(dueEvent())

        // Idempotency key is the deterministic per-execution key carried on the event.
        assertThat(key.captured).isEqualTo("so-exec-$orderId-2026-07-13")
        assertThat(req.captured.type).isEqualTo("SCT")
        // 220000 CZK minor units (2 fraction digits) -> 2200.00 major units.
        assertThat(req.captured.amount).isEqualByComparingTo("2200.00")
        assertThat(req.captured.debtorIban).isEqualTo("DE89370400440532013001")
        coVerify(exactly = 1) { useCase.confirmExecution(orderId) }
        coVerify(exactly = 0) { useCase.recordFailure(any()) }
    }

    @Test
    fun `SEPA_CREDIT transfer rejection records a failure`() = runBlocking {
        every { sepaClient.createPayment(any(), any()) } returns
            Uni.createFrom().item(Response.status(422).build())
        coEvery { useCase.recordFailure(orderId) } returns mockk(relaxed = true)

        consumer.consume(dueEvent())

        coVerify(exactly = 1) { useCase.recordFailure(orderId) }
        coVerify(exactly = 0) { useCase.confirmExecution(any()) }
    }

    @Test
    fun `SEPA_CREDIT order missing debtor details records a failure without calling the rail`() = runBlocking {
        coEvery { useCase.recordFailure(orderId) } returns mockk(relaxed = true)

        consumer.consume(dueEvent(debtorIban = null))

        coVerify(exactly = 1) { useCase.recordFailure(orderId) }
        coVerify(exactly = 0) { sepaClient.createPayment(any(), any()) }
    }

    @Test
    fun `DOMESTIC order is not yet wired to a rail and records a failure`() = runBlocking {
        coEvery { useCase.recordFailure(orderId) } returns mockk(relaxed = true)

        consumer.consume(dueEvent(paymentType = "DOMESTIC"))

        coVerify(exactly = 1) { useCase.recordFailure(orderId) }
        coVerify(exactly = 0) { sepaClient.createPayment(any(), any()) }
    }

    @Test
    fun `a failed event (no paymentType) is ignored`() = runBlocking {
        val failedEvent = mapper.writeValueAsString(
            mapOf("orderId" to orderId, "partyId" to orderId, "failureCount" to 3, "status" to "FAILED"),
        )

        consumer.consume(failedEvent)

        coVerify(exactly = 0) { sepaClient.createPayment(any(), any()) }
        coVerify(exactly = 0) { useCase.confirmExecution(any()) }
        coVerify(exactly = 0) { useCase.recordFailure(any()) }
    }
}
