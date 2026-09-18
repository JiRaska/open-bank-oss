// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.settlement.infrastructure.adapter

import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementProtocol
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.infrastructure.client.BalanceRestClient
import com.openbank.settlement.infrastructure.client.SettlementCoverRequest
import com.openbank.settlement.infrastructure.client.SettlementCoverResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SettlementCoverAdapterTest {
    private val row = Settlement(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal("125.50"), "CZK",
        SettlementStatus.PENDING, Instant.now(), Instant.now(), SettlementProtocol.LEDGER_PROJECTION,
    )
    private val client = mockk<BalanceRestClient>()
    private val repository = mockk<SettlementRepository>()
    private val adapter = SettlementCoverAdapter(client, repository)
    private val response = SettlementCoverResponse(
        UUID.randomUUID(),
        row.payerAccountId,
        row.amount,
        row.currency,
        row.id.toString(),
        null,
        null,
    )

    @Test
    fun `cover reserves payer with the journal transaction reference and no expiry`(): Unit = runBlocking {
        coEvery { repository.findById(row.id) } returns row
        val request = slot<SettlementCoverRequest>()
        every { client.reserve(row.payerAccountId, capture(request)) } returns Uni.createFrom().item(response)
        adapter.reservePayer(row.id)
        assertThat(request.captured.amount).isEqualByComparingTo(row.amount)
        assertThat(request.captured.currency).isEqualTo(row.currency)
        assertThat(request.captured.referenceId).isEqualTo(row.id.toString())
        assertThat(request.captured.ttlSeconds).isNull()
        verify(exactly = 0) {
            client.debit(any(), any())
            client.credit(any(), any())
        }
    }

    @Test
    fun `legacy rows cannot obtain cover through the new protocol`() {
        coEvery { repository.findById(row.id) } returns row.copy(protocol = SettlementProtocol.LEGACY)
        assertThatThrownBy {
            runBlocking { adapter.reservePayer(row.id) }
        }.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { client.reserve(any(), any()) }
    }

    @Test
    fun `mismatched expired or released cover never permits ledger booking`() {
        coEvery { repository.findById(row.id) } returns row
        listOf(
            response.copy(accountId = row.payeeAccountId),
            response.copy(currency = "EUR"),
            response.copy(referenceId = UUID.randomUUID().toString()),
            response.copy(amount = BigDecimal.ONE),
            response.copy(expiresAt = "2026-01-01T00:00:00Z"),
            response.copy(releasedAt = "2026-01-01T00:00:00Z"),
        ).forEach { invalid ->
            every { client.reserve(any(), any()) } returns Uni.createFrom().item(invalid)
            assertThatThrownBy { runBlocking { adapter.reservePayer(row.id) } }
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `lost hold response remains an unknown outcome`() {
        coEvery { repository.findById(row.id) } returns row
        every { client.reserve(any(), any()) } returns Uni.createFrom().failure(IllegalStateException("response lost"))
        assertThatThrownBy { runBlocking { adapter.reservePayer(row.id) } }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("response lost")
    }
}
