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
import com.openbank.sepa.infrastructure.observability.SepaSettlementMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
    private val registry = SimpleMeterRegistry()
    private val metrics = SepaSettlementMetrics().also { it.bindTo(registry) }
    private val adapter = SettlementAdapter(
        client,
        Clock.fixed(Instant.parse("2026-01-20T10:00:00Z"), ZoneOffset.UTC),
        metrics,
    ).also { it.self = it }

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
    fun `instant marker requests SEPA_INST ONE_OFF and accepts only its matching completed echo`(): Unit = runBlocking {
        val sent = slot<InitiateTransactionRequest>()
        every { client.initiateTransaction(capture(sent)) } returns
            Uni.createFrom().item(Response.status(201).entity(matching().copy(rail = "SEPA_INST")).build())
        val p = payment().copy(type = SepaPaymentType.SCT_INST)

        val outcome = adapter.settle(p)

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isEqualTo(txId)
        assertThat(sent.captured.rail).isEqualTo("SEPA_INST")
        assertThat(sent.captured.instructionType).isEqualTo("ONE_OFF")
        assertThat(sent.captured.idempotencyKey).isEqualTo("sepa-settlement-${p.id}")
        verify(exactly = 1) { client.initiateTransaction(any()) }
    }

    @Test
    fun `instant replay of an old SEPA_CT booking stays unresolved under the original key`(): Unit = runBlocking {
        // Each HTTP response echoes the same existing booking; closing one must not affect the next.
        every { client.initiateTransaction(any()) } answers {
            Uni.createFrom().item(Response.status(201).entity(matching()).build())
        }
        val p = payment().copy(type = SepaPaymentType.SCT_INST)

        repeat(2) {
            val failure = runCatching { adapter.settle(p) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SettlementUnavailableException::class.java)
                .hasMessageContaining("does not match")
        }

        verify(exactly = 2) {
            client.initiateTransaction(
                match {
                    it.idempotencyKey == "sepa-settlement-${p.id}" &&
                        it.rail == "SEPA_INST" &&
                        it.instructionType == "ONE_OFF"
                },
            )
        }
        verify(exactly = 2) { client.initiateTransaction(any()) }
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

    private fun counts(): Map<String, Double> = SepaSettlementMetrics.Outcome.entries.associate { outcome ->
        outcome.label to registry.get(SepaSettlementMetrics.METRIC)
            .tags("rail", "SEPA", "outcome", outcome.label).counter().count()
    }

    @Test
    fun `each failed provider call counts one bounded classified outcome only`(): Unit = runBlocking {
        data class Case(val tx: TransactionResponse?, val http: Int, val expected: String)
        val cases = listOf(
            Case(matching().copy(amount = BigDecimal("99.00")), 201, "mismatch"),
            Case(matching().copy(id = null), 201, "mismatch"),
            Case(matching().copy(amount = null), 201, "mismatch"),
            Case(matching("FAILED"), 201, "failed-replay"),
            Case(matching("PENDING"), 201, "unexpected-status"),
            Case(null, 201, "unexpected-status"),
            Case(null, 409, "conflict"),
            Case(null, 400, "unexpected-status"),
            Case(null, 422, "unexpected-status"),
            Case(matching(), 200, "unexpected-status"),
            Case(matching(), 202, "unexpected-status"),
            Case(null, 500, "unexpected-status"),
        )
        val expected = SepaSettlementMetrics.Outcome.entries.associate { it.label to 0.0 }.toMutableMap()
        for (case in cases) {
            answer(case.tx, case.http)
            assertThat(runCatching { adapter.settle(payment()) }.exceptionOrNull())
                .isInstanceOf(SettlementUnavailableException::class.java)
            expected[case.expected] = expected.getValue(case.expected) + 1.0
            assertThat(counts()).isEqualTo(expected)
        }
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().failure(IllegalStateException("synthetic transport"))
        assertThat(runCatching { adapter.settle(payment()) }.exceptionOrNull())
            .isInstanceOf(SettlementUnavailableException::class.java)
        expected["transport-error"] = 1.0
        assertThat(counts()).isEqualTo(expected)
    }

    @Test
    fun `matching SCT and instant calls count no unsettled attempt`(): Unit = runBlocking {
        for (type in listOf(SepaPaymentType.SCT, SepaPaymentType.SCT_INST)) {
            answer(matching().copy(rail = if (type == SepaPaymentType.SCT) "SEPA_CT" else "SEPA_INST"))
            assertThat(adapter.settle(payment().copy(type = type)).settled).isTrue()
        }
        assertThat(counts().values).containsOnly(0.0)
    }

    @Test
    fun `FAILED attempts retain same key and count once without outer double count`(): Unit = runBlocking {
        val p = payment()
        val sent = mutableListOf<InitiateTransactionRequest>()
        every { client.initiateTransaction(capture(sent)) } answers {
            Uni.createFrom().item(Response.status(201).entity(matching("FAILED")).build())
        }
        repeat(3) {
            assertThat(runCatching { adapter.settle(p) }.exceptionOrNull())
                .isInstanceOf(SettlementUnavailableException::class.java)
        }
        assertThat(sent.map { it.idempotencyKey }).containsOnly("sepa-settlement-${p.id}")
        assertThat(counts().getValue("failed-replay")).isEqualTo(3.0)
        assertThat(counts().values.sum()).isEqualTo(3.0)
        verify(exactly = 3) { client.initiateTransaction(any()) }
    }

    @Test
    fun `cancellation propagates its original cause and increments nothing`(): Unit = runBlocking {
        val cancellation = CancellationException("synthetic cancellation")
        every { client.initiateTransaction(any()) } returns Uni.createFrom().failure(cancellation)
        val failure = runCatching { adapter.settle(payment()) }.exceptionOrNull()
        // Coroutine stack-trace recovery may copy the exception while retaining the original as its cause.
        assertThat(failure).isInstanceOf(CancellationException::class.java).hasMessage("synthetic cancellation")
        assertThat(generateSequence(failure) { it.cause }.any { it === cancellation }).isTrue()
        assertThat(counts().values).containsOnly(0.0)
    }
}
