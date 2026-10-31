// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.clients.transaction.model.TransactionResponse
import com.openbank.libs.domain.money.Money
import com.openbank.sepainstant.application.port.out.SettlementUnavailableException
import com.openbank.sepainstant.domain.model.SctInstPayment
import com.openbank.sepainstant.domain.model.SctInstStatus
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SettlementAdapterTest {

    private val client = mockk<TransactionServiceClient>()

    /** Expose the self-injection point so tests can call resilience method directly. */
    private val adapter = object : SettlementAdapter(client) {
        init {
            @Suppress("LeakingThis")
            self = this
            // ADR-0100: settle() formats the value date via LocalDate.now(clock); a fixed
            // clock keeps it deterministic (the assertions don't depend on the date).
            clock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC)
        }
    }

    private val fixedNow = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC)

    private fun payment(id: Long = 42L) = SctInstPayment(
        id = id,
        idempotencyKey = "idem-settle-1",
        status = SctInstStatus.PROCESSING,
        debtorAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice",
        creditorIban = "FR1420041010050500013M02606",
        creditorName = "Bob",
        creditorBic = "BNPAFRPPXXX",
        amount = Money.of("99.50", "EUR"),
        remittanceInfo = "Test",
        endToEndId = "E2E-SETTLE-1",
        executionTimeoutAt = null,
        settledAt = null,
        recalledAt = null,
        recallReason = null,
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    private fun completed(
        id: UUID,
        status: String = "COMPLETED",
        amount: String = "99.50",
        valueDate: String = "2026-01-01",
    ) = TransactionResponse(
        id = id,
        status = status,
        type = "DEBIT",
        sourceAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        amount = BigDecimal(amount),
        currencyCode = "EUR",
        rail = "SEPA_INST",
        instructionType = "ONE_OFF",
        valueDate = LocalDate.parse(valueDate),
    )

    private fun created(tx: TransactionResponse, status: Int = 201): Response =
        Response.status(status).entity(tx).build()

    @Test
    fun `HTTP 200 and 202 are not accepted - only 201 maps to a result, as before`() {
        listOf(200, 202).forEach { code ->
            every { client.initiateTransaction(any()) } returns
                Uni.createFrom().item(created(completed(UUID.randomUUID()), code))

            assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
                .describedAs("HTTP %d", code)
                .isInstanceOf(SettlementUnavailableException::class.java)
        }
    }

    @Test
    fun `refusal diagnostics never expose the provider body or its read error`() {
        val poison = "synthetic-private-provider-payload"
        listOf(400, 422).forEach { code ->
            listOf(false, true).forEach { readFails ->
                val response = mockk<Response>(relaxed = true)
                every { response.status } returns code
                every { response.hasEntity() } returns true
                if (readFails) {
                    every { response.readEntity(InputStream::class.java) } throws IllegalStateException(poison)
                } else {
                    every { response.readEntity(InputStream::class.java) } returns
                        ByteArrayInputStream(poison.toByteArray())
                }
                every { client.initiateTransaction(any()) } returns Uni.createFrom().item(response)

                assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
                    .isInstanceOf(SettlementUnavailableException::class.java)
                    .hasRootCauseMessage("Unexpected HTTP $code from transaction-service")
                    .isInstanceOfSatisfying(SettlementUnavailableException::class.java) { failure ->
                        assertThat(failure.stackTraceToString()).doesNotContain(poison)
                    }
            }
        }
    }

    @Test
    fun `unexpected successful statuses never expose the transaction body`() {
        val poison = "synthetic-private-transaction-payload"
        val txId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val tx = completed(txId).copy(type = poison)
        listOf(200, 202, 206).forEach { code ->
            every { client.initiateTransaction(any()) } returns Uni.createFrom().item(created(tx, code))

            assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
                .isInstanceOf(SettlementUnavailableException::class.java)
                .hasRootCauseMessage("Unexpected HTTP $code from transaction-service")
                .isInstanceOfSatisfying(SettlementUnavailableException::class.java) { failure ->
                    assertThat(failure.stackTraceToString())
                        .doesNotContain(poison, txId.toString(), tx.sourceAccountId.toString())
                }
        }
    }

    @Test
    fun `HTTP 201 with an unreadable body is unproven - retried, never settled`() {
        val response = mockk<Response>(relaxed = true)
        every { response.status } returns 201
        every { response.hasEntity() } returns true
        every { response.readEntity(TransactionResponse::class.java) } throws IllegalStateException("garbled")
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(response)

        assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `HTTP 201 with matching COMPLETED body returns settled=true`() {
        val txId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val response = created(completed(txId))
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(response)

        val outcome = adapter.settle(payment()).await().indefinitely()

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isEqualTo(txId)
    }

    @Test
    fun `HTTP 201 uses matching entity id even without parseable Location`() {
        val txId = UUID.randomUUID()
        val response = created(completed(txId))
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(response)

        val outcome = adapter.settle(payment()).await().indefinitely()

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isEqualTo(txId)
    }

    @Test
    fun `provider-adjusted value date does not hide a completed booking`() {
        val txId = UUID.randomUUID()
        val response = created(completed(txId, valueDate = "2026-01-05"))
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(response)

        assertThat(adapter.settle(payment()).await().indefinitely().settled).isTrue()
    }

    @Test
    fun `HTTP 409 conflict is not proof of booking`() {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().failure(WebApplicationException(409))

        assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `HTTP 201 with a pending transaction is unproven - the facade throws, the payment is retried`() {
        val txId = UUID.randomUUID()
        val response = created(completed(txId, status = "PENDING"))
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(response)

        assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
            .isInstanceOf(SettlementUnavailableException::class.java)
            .hasRootCauseInstanceOf(TransactionCompletionUncertainException::class.java)
    }

    @Test
    fun `HTTP 201 with failed transaction is not proof of booking`() {
        val txId = UUID.randomUUID()
        val response = created(completed(txId, status = "FAILED"))
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(response)

        assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `HTTP 201 with mismatched amount is not proof of this payment booking`() {
        val txId = UUID.randomUUID()
        val response = created(completed(txId, amount = "100.00"))
        every { client.initiateTransaction(any()) } returns Uni.createFrom().item(response)

        assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `HTTP 500 throws SettlementUnavailableException`() {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().failure(WebApplicationException(500))

        assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `network failure throws SettlementUnavailableException`() {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `idempotency key is prefixed with sct-inst-settlement and uses payment id`() {
        val txId = UUID.randomUUID()
        val captured = mutableListOf<String>()
        every { client.initiateTransaction(match { captured.add(it.idempotencyKey) }) } returns
            Uni.createFrom().item(created(completed(txId)))

        adapter.settle(payment(id = 7L)).await().indefinitely()

        assertThat(captured.single()).isEqualTo("sct-inst-settlement-7")
    }
}
