// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** Unit coverage for [LedgerCallGuard] — the fault-tolerance-annotated delegation to [LedgerRestClient]. */
class LedgerCallGuardTest {

    private val ledgerClient: LedgerRestClient = mockk()
    private val guard = LedgerCallGuard(ledgerClient)

    @Test
    fun `postJournal delegates to the ledger client and returns its response`() {
        val transactionId = UUID.randomUUID()
        val journalId = UUID.randomUUID()
        val request = PostJournalRequest(
            idempotencyKey = "key-1",
            transactionId = transactionId,
            entryDate = "2026-07-14",
            valueDate = "2026-07-14",
            description = "test journal",
            lines = listOf(
                JournalLineRequest(
                    glAccountId = UUID.randomUUID(),
                    side = "DEBIT",
                    amount = BigDecimal("10.00"),
                    currencyCode = "CZK",
                    fxRate = null,
                    baseAmount = BigDecimal("10.00"),
                    baseCurrencyCode = "CZK",
                ),
            ),
            createdBy = UUID.randomUUID(),
        )
        every { ledgerClient.postJournal(request) } returns
            Uni.createFrom().item(JournalResponse(journalId, transactionId, "POSTED"))

        val result = guard.postJournal(request).await().indefinitely()

        assertThat(result.id).isEqualTo(journalId)
        assertThat(result.status).isEqualTo("POSTED")
        verify(exactly = 1) { ledgerClient.postJournal(request) }
    }

    @Test
    fun `reverseJournal delegates to the ledger client with the journal id and reason`() {
        val journalId = UUID.randomUUID()
        val reversedBy = UUID.randomUUID()
        val request = ReverseJournalRequest(reason = "duplicate", reversedBy = reversedBy)
        every { ledgerClient.reverseJournal(journalId, request) } returns
            Uni.createFrom().item(JournalResponse(journalId, UUID.randomUUID(), "REVERSED"))

        val result = guard.reverseJournal(journalId, request).await().indefinitely()

        assertThat(result.status).isEqualTo("REVERSED")
        verify(exactly = 1) { ledgerClient.reverseJournal(journalId, request) }
    }
}
