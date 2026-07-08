// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.billing.infrastructure.adapter.LedgerPostingAdapter
import com.openbank.billing.infrastructure.client.BillingLedgerConfig
import com.openbank.billing.infrastructure.client.LedgerJournalEntryResponse
import com.openbank.billing.infrastructure.client.LedgerPostJournalRequest
import com.openbank.billing.infrastructure.client.LedgerRestClient
import io.mockk.CapturingSlot
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The REST ledger adapter must hand the ledger client a balanced journal built from the
 * configured GL chart, dated by the injected clock, and surface (not swallow) a ledger failure
 * so the outbox dispatcher's own `markFailed` retry loop sees it. Mirrors
 * `openbank-lending-service`'s `RestLedgerPostingAdapterTest`.
 */
class LedgerPostingAdapterTest {

    private val ledgerClient = mockk<LedgerRestClient>()
    private val clock = Clock.fixed(Instant.parse("2026-07-01T03:00:00Z"), ZoneOffset.UTC)

    private val config = object : BillingLedgerConfig {
        override fun systemActorId(): UUID = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
        override fun gl(): BillingLedgerConfig.Gl = object : BillingLedgerConfig.Gl {
            override fun feeReceivable(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000001400")
            override fun feeIncome(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004001")
        }
    }

    private val adapter = LedgerPostingAdapter(ledgerClient, config, clock)

    private fun command(idempotencyKey: String = "fee-2026-07-acc-1-f1-CZK") = FeeJournalCommand(
        idempotencyKey = idempotencyKey,
        cycleId = "2026-07",
        accountId = "acc-1",
        feeId = "f1",
        amount = BigDecimal("150.00"),
        currency = "CZK",
        description = "Fee charge: Maintenance",
    )

    @Test
    fun `posts a clock-dated balanced journal built from the configured GL accounts and returns the journal id`() {
        val requestSlot: CapturingSlot<LedgerPostJournalRequest> = slot()
        val journalId = UUID.randomUUID()
        every { ledgerClient.postJournal(capture(requestSlot)) } returns Uni.createFrom().item(
            LedgerJournalEntryResponse(id = journalId, transactionId = UUID.randomUUID(), status = "POSTED"),
        )

        val result = runBlocking { adapter.post(command()) }

        assertThat(result).isEqualTo(journalId)
        val request = requestSlot.captured
        assertThat(request.idempotencyKey).isEqualTo("fee-2026-07-acc-1-f1-CZK")
        assertThat(request.entryDate).isEqualTo("2026-07-01")
        assertThat(request.valueDate).isEqualTo("2026-07-01")
        assertThat(request.createdBy).isEqualTo(config.systemActorId())
        assertThat(request.lines).hasSize(2)
        verify(exactly = 1) { ledgerClient.postJournal(any()) }
    }

    @Test
    fun `a ledger failure propagates instead of being swallowed`() {
        every { ledgerClient.postJournal(any()) } returns Uni.createFrom().failure(IllegalStateException("ledger down"))

        assertThatThrownBy { runBlocking { adapter.post(command()) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ledger down")
    }
}
