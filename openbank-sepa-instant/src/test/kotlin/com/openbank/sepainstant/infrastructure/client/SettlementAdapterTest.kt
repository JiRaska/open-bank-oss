// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.money.Money
import com.openbank.sepainstant.application.port.out.SettlementUnavailableException
import com.openbank.sepainstant.domain.model.SctInstPayment
import com.openbank.sepainstant.domain.model.SctInstStatus
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
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
            objectMapper = ObjectMapper()
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

    private fun completedBody(
        id: UUID,
        status: String = "COMPLETED",
        amount: String = "99.50",
        valueDate: String = "2026-01-01",
    ): String = """
        {
          "id":"$id","status":"$status","type":"DEBIT",
          "sourceAccountId":"00000000-0000-0000-0000-000000000001",
          "amount":$amount,"currencyCode":"EUR","rail":"SEPA_INST",
          "instructionType":"ONE_OFF","valueDate":"$valueDate"
        }
    """.trimIndent()

    @Test
    fun `HTTP 201 with matching COMPLETED body returns settled=true`() {
        val txId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val response = Response.created(URI.create("/api/v1/transactions/$txId"))
            .entity(completedBody(txId)).build()
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        val outcome = adapter.settle(payment()).await().indefinitely()

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isEqualTo(txId)
    }

    @Test
    fun `HTTP 201 uses matching entity id even without parseable Location`() {
        val txId = UUID.randomUUID()
        val response = Response.created(URI.create("/api/v1/transactions/not-a-uuid"))
            .entity(completedBody(txId)).build()
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        val outcome = adapter.settle(payment()).await().indefinitely()

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isEqualTo(txId)
    }

    @Test
    fun `provider-adjusted value date does not hide a completed booking`() {
        val txId = UUID.randomUUID()
        val response = Response.created(URI.create("/api/v1/transactions/$txId"))
            .entity(completedBody(txId, valueDate = "2026-01-05")).build()
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        assertThat(adapter.settle(payment()).await().indefinitely().settled).isTrue()
    }

    @Test
    fun `HTTP 409 conflict is not proof of booking`() {
        val response = Response.status(Response.Status.CONFLICT).build()
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `HTTP 201 with a pending transaction keeps payment processing`() {
        val txId = UUID.randomUUID()
        val response = Response.created(URI.create("/api/v1/transactions/$txId"))
            .entity(completedBody(txId, status = "PENDING")).build()
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        assertThat(adapter.settle(payment()).await().indefinitely().settled).isFalse()
    }

    @Test
    fun `HTTP 201 with failed transaction is not proof of booking`() {
        val txId = UUID.randomUUID()
        val response = Response.created(URI.create("/api/v1/transactions/$txId"))
            .entity(completedBody(txId, status = "FAILED")).build()
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `HTTP 201 with mismatched amount is not proof of this payment booking`() {
        val txId = UUID.randomUUID()
        val response = Response.created(URI.create("/api/v1/transactions/$txId"))
            .entity(completedBody(txId, amount = "100.00")).build()
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `HTTP 500 throws SettlementUnavailableException`() {
        val response = Response.serverError().build()
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `network failure throws SettlementUnavailableException`() {
        every { client.initiateTransaction(any(), any()) } returns
            Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThatThrownBy { adapter.settle(payment()).await().indefinitely() }
            .isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `idempotency key is prefixed with sct-inst-settlement and uses payment id`() {
        val txId = UUID.randomUUID()
        val response = Response.created(URI.create("/api/v1/transactions/$txId"))
            .entity(completedBody(txId)).build()
        val capturedKey = mutableListOf<String>()
        every { client.initiateTransaction(capture(capturedKey), any()) } returns Uni.createFrom().item(response)

        adapter.settle(payment(id = 7L)).await().indefinitely()

        assertThat(capturedKey.single()).isEqualTo("sct-inst-settlement-7")
    }
}
