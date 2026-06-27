// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

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
import java.math.BigDecimal
import java.net.URI
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
        amount = BigDecimal("99.50"),
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

    @Test
    fun `HTTP 201 with Location header returns settled=true and parsed transactionId`() {
        val txId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val response = Response.created(URI.create("/api/v1/transactions/$txId")).build()
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        val outcome = adapter.settle(payment()).await().indefinitely()

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isEqualTo(txId)
    }

    @Test
    fun `HTTP 201 without parseable Location returns settled=true with null transactionId`() {
        val response = Response.created(URI.create("/api/v1/transactions/not-a-uuid")).build()
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        val outcome = adapter.settle(payment()).await().indefinitely()

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isNull()
    }

    @Test
    fun `HTTP 409 conflict is treated as idempotent settled=true`() {
        val response = Response.status(Response.Status.CONFLICT).build()
        every { client.initiateTransaction(any(), any()) } returns Uni.createFrom().item(response)

        val outcome = adapter.settle(payment()).await().indefinitely()

        assertThat(outcome.settled).isTrue()
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
        val response = Response.created(URI.create("/api/v1/transactions/$txId")).build()
        val capturedKey = mutableListOf<String>()
        every { client.initiateTransaction(capture(capturedKey), any()) } returns Uni.createFrom().item(response)

        adapter.settle(payment(id = 7L)).await().indefinitely()

        assertThat(capturedKey.single()).isEqualTo("sct-inst-settlement-7")
    }
}
