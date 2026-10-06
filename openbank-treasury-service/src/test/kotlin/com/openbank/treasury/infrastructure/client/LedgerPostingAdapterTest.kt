// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.client

import com.openbank.treasury.application.port.out.LedgerUnavailableException
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingLine
import com.openbank.treasury.domain.model.Side
import com.openbank.treasury.domain.model.TreasuryChart
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class LedgerPostingAdapterTest {
    private val client = mockk<LedgerRestClient>()
    private val adapter = LedgerPostingAdapter(client)
    private val date = LocalDate.parse("2026-10-01")
    private val description = "Treasury settlement"
    private val spec = JournalSpec(
        UUID.randomUUID(),
        PostingEvent.SETTLED,
        listOf(
            PostingLine("1500", Side.DEBIT, BigDecimal("100000.00"), "CZK"),
            PostingLine("1001", Side.CREDIT, BigDecimal("100000.00"), "CZK"),
        ),
    )

    private fun journal(status: String = "POSTED", amount: BigDecimal = BigDecimal("100000.0")) = JournalDetailResponse(
        UUID.randomUUID(),
        spec.dealId,
        date.toString(),
        date.toString(),
        description,
        status,
        true,
        listOf(
            JournalDetailLine(TreasuryChart.glAccountId("1500"), "DEBIT", amount, "CZK", amount, "CZK", 1),
            JournalDetailLine(TreasuryChart.glAccountId("1001"), "CREDIT", amount, "CZK", amount, "CZK", 2),
        ),
    )

    @Test
    fun `matching synthetic journal permits idempotent settlement retry`() = runBlocking<Unit> {
        val posted = journal()
        every { client.findByIdempotencyKey(spec.idempotencyKey) } returns
            Uni.createFrom().item(JournalLookupResponse(true, posted))
        assertThat(adapter.findPostedJournal(spec, date, description)).isEqualTo(posted.id)
    }

    @Test
    fun `explicit absent key permits normal funding check`() = runBlocking<Unit> {
        every { client.findByIdempotencyKey(spec.idempotencyKey) } returns
            Uni.createFrom().item(JournalLookupResponse(false, null))
        assertThat(adapter.findPostedJournal(spec, date, description)).isNull()
    }

    @Test
    fun `missing lookup route cannot be treated as absent key`() {
        every { client.findByIdempotencyKey(spec.idempotencyKey) } returns
            Uni.createFrom().failure(WebApplicationException(404))
        assertThatThrownBy { runBlocking { adapter.findPostedJournal(spec, date, description) } }
            .isInstanceOf(LedgerUnavailableException::class.java)
    }

    @Test
    fun `contradictory lookup responses fail closed`() {
        listOf(JournalLookupResponse(false, journal()), JournalLookupResponse(true, null)).forEach { contradiction ->
            every { client.findByIdempotencyKey(spec.idempotencyKey) } returns Uni.createFrom().item(contradiction)
            assertThatThrownBy { runBlocking { adapter.findPostedJournal(spec, date, description) } }
                .isInstanceOf(LedgerUnavailableException::class.java)
        }
    }

    @Test
    fun `altered or reversed journal fails closed`() {
        listOf(journal(status = "REVERSED"), journal(amount = BigDecimal("99999"))).forEach { mismatch ->
            every { client.findByIdempotencyKey(spec.idempotencyKey) } returns
                Uni.createFrom().item(JournalLookupResponse(true, mismatch))
            assertThatThrownBy { runBlocking { adapter.findPostedJournal(spec, date, description) } }
                .isInstanceOf(LedgerUnavailableException::class.java)
        }
    }
}
