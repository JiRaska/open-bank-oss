// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class LedgerCallGuardTest {

    @Test
    fun `delegates the journal post to the ledger rest client unchanged`() {
        val client = mockk<LedgerRestClient>()
        val gl = UUID.fromString("a0000000-0000-0000-0000-000000001200")
        val request = PostJournalRequest(
            idempotencyKey = "loan:42:disbursement",
            transactionId = UUID.nameUUIDFromBytes("loan:42:disbursement".toByteArray()),
            entryDate = "2026-05-30",
            valueDate = "2026-05-30",
            description = "Lending disbursement: loan:42:disbursement",
            lines = listOf(
                JournalLineRequest(gl, "DEBIT", BigDecimal("12000.00"), "EUR", null, BigDecimal("12000.00"), "EUR"),
            ),
            createdBy = UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
        )
        val response = JournalResponse(id = UUID.randomUUID(), transactionId = request.transactionId, status = "POSTED")
        every { client.postJournal(request) } returns Uni.createFrom().item(response)

        val result = LedgerCallGuard(client).postJournal(request).await().indefinitely()

        assertThat(result).isEqualTo(response)
    }
}
