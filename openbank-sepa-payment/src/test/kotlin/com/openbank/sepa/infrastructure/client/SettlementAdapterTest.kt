// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.clients.transaction.model.InitiateTransactionRequest
import com.openbank.clients.transaction.model.TransactionResponse
import com.openbank.libs.domain.money.Money
import com.openbank.sepa.application.port.out.SettlementUnavailableException
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class SettlementAdapterTest {
    private val client = mockk<TransactionServiceClient>()
    private val adapter = SettlementAdapter(client, Clock.fixed(Instant.parse("2026-01-20T10:00:00Z"), ZoneOffset.UTC))
        .also { it.self = it }

    private val txId = UUID.randomUUID()
    private val debtor = UUID.randomUUID()

    private fun payment() = SepaPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-1",
        type = SepaPaymentType.SCT,
        status = SepaPaymentStatus.PROCESSING,
        debtorAccountId = debtor,
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Debtor",
        creditorIban = "FR1420041010050500013M02606",
        creditorName = "Bob Creditor",
        creditorBic = "BNPAFRPPXXX",
        amount = Money.of(BigDecimal("12.34"), "EUR"),
        remittanceInfo = "Invoice 1",
        endToEndId = "E2E-0001",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = Instant.now(),
        completedAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun matching(status: String = "COMPLETED") = TransactionResponse(
        id = txId,
        status = status,
        type = "TRANSFER",
        sourceAccountId = debtor,
        amount = BigDecimal("12.340"),
        currencyCode = "EUR",
        rail = "SEPA_CT",
        instructionType = "ONE_OFF",
        // A later business day than the request: valueDate is deliberately not part of the binding.
        valueDate = LocalDate.parse("2026-01-21"),
    )

    private fun answer(tx: TransactionResponse?, status: Int = 201) {
        val builder = Response.status(status)
        if (tx != null) builder.entity(tx)
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(builder.build())
    }

    private fun settleFails(): Throwable = runCatching { runBlocking { adapter.settle(payment()) } }
        .exceptionOrNull() ?: error("settle() unexpectedly succeeded")

    @Test
    fun `a matching COMPLETED 201 settles with the booked transaction id and the payment-scoped key`(): Unit =
        runBlocking {
            val sent = slot<InitiateTransactionRequest>()
            every { client.initiateTransaction(capture(sent)) } returns
                Uni.createFrom().item(Response.status(201).entity(matching()).build())
            val p = payment()

            val outcome = adapter.settle(p)

            assertThat(outcome.settled).isTrue()
            assertThat(outcome.transactionId).isEqualTo(txId)
            assertThat(sent.captured.idempotencyKey).isEqualTo("sepa-settlement-${p.id}")
            // transaction-service parses instructionType strictly; the SEPA scheme name is not an InstructionType.
            assertThat(sent.captured.instructionType).isEqualTo("ONE_OFF")
            assertThat(sent.captured.rail).isEqualTo("SEPA_CT")
        }

    @Test
    fun `each mismatched field of a COMPLETED 201 is not a settlement`() {
        val mismatches = mapOf(
            "type" to matching().copy(type = "DEBIT"),
            "account" to matching().copy(sourceAccountId = UUID.randomUUID()),
            "amount" to matching().copy(amount = BigDecimal("12.35")),
            "currency" to matching().copy(currencyCode = "CZK"),
            "rail" to matching().copy(rail = "SEPA_INST"),
            "instructionType" to matching().copy(instructionType = "STANDING_ORDER"),
        )
        mismatches.forEach { (field, tx) ->
            answer(tx)
            assertThat(settleFails())
                .describedAs("mismatched %s", field)
                .isInstanceOf(SettlementUnavailableException::class.java)
                .hasMessageContaining("does not match")
        }
    }

    @Test
    fun `a 202 or 200 with a COMPLETED body is not the provider's answer to a create and is not settled`() {
        listOf(200, 202).forEach { code ->
            answer(matching(), code)
            assertThat(settleFails()).describedAs("HTTP %d", code)
                .isInstanceOf(SettlementUnavailableException::class.java)
                .hasMessageContaining("Unexpected HTTP $code")
        }
    }

    @Test
    fun `a PENDING 201 or 202 is completion-uncertain - held, never settled`() {
        listOf(201, 202).forEach { code ->
            answer(matching(status = "PENDING"), code)
            val failure = settleFails()
            assertThat(failure).isInstanceOf(SettlementUnavailableException::class.java)
            assertThat(failure).hasCauseInstanceOf(TransactionCompletionUncertainException::class.java)
        }
    }

    @Test
    fun `a 201 with no readable body is completion-uncertain, not settled`() {
        answer(null)
        assertThat(settleFails()).hasCauseInstanceOf(TransactionCompletionUncertainException::class.java)
    }

    @Test
    fun `HTTP 409 is a concurrent-state conflict - not settled`() {
        answer(null, 409)
        assertThat(settleFails())
            .isInstanceOf(SettlementUnavailableException::class.java)
            .hasMessageContaining("409")
    }

    @Test
    fun `HTTP 500 from transaction-service throws SettlementUnavailableException`() {
        answer(null, 500)
        assertThat(settleFails()).isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `connection failure throws SettlementUnavailableException`() {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().failure(RuntimeException("connection refused"))
        assertThat(settleFails()).isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `cancellation is rethrown, not converted into an outage`() {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().failure(CancellationException("worker shutting down"))
        assertThatThrownBy { runBlocking { adapter.settle(payment()) } }
            .isInstanceOf(CancellationException::class.java)
            .isNotInstanceOf(SettlementUnavailableException::class.java)
    }
}
