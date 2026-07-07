// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.adapter

import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.lending.infrastructure.client.JournalResponse
import com.openbank.lending.infrastructure.client.LedgerCallGuard
import com.openbank.lending.infrastructure.client.LendingGlAccounts
import com.openbank.lending.infrastructure.client.LendingLedgerConfig
import com.openbank.lending.infrastructure.client.PostJournalRequest
import com.openbank.libs.domain.money.Money
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The REST ledger adapter must hand the guard a balanced journal built from the configured GL chart,
 * dated by the injected clock — and surface (not swallow) a ledger failure so the posting Uni fails
 * the surrounding business operation.
 */
class RestLedgerPostingAdapterTest {

    private val guard = mockk<LedgerCallGuard>()
    private val config = mockk<LendingLedgerConfig>()
    private val clock = Clock.fixed(Instant.parse("2026-05-30T12:00:00Z"), ZoneOffset.UTC)

    private val accounts = LendingGlAccounts(
        loansReceivable = UUID.fromString("a0000000-0000-0000-0000-000000001200"),
        fundingClearing = UUID.fromString("a0000000-0000-0000-0000-000000001100"),
        interestIncome = UUID.fromString("a0000000-0000-0000-0000-000000004100"),
        interestReceivable = UUID.fromString("a0000000-0000-0000-0000-000000001300"),
        loanLossExpense = UUID.fromString("a0000000-0000-0000-0000-000000005100"),
    )
    private val actor = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val partyId = UUID.fromString("55555555-5555-5555-5555-555555555555")

    private val adapter = RestLedgerPostingAdapter(guard, config, clock)

    private fun posting(kind: PostingKind = PostingKind.DISBURSEMENT) = LedgerPosting(
        reference = "loan:42:disbursement",
        partyId = partyId,
        amount = Money.of("12000.00", "EUR"),
        kind = kind,
    )

    @Test
    fun `posts a clock-dated journal built from the configured GL accounts`() {
        val requestSlot: CapturingSlot<PostJournalRequest> = slot()
        every { config.accounts() } returns accounts
        every { config.systemActorId() } returns actor
        every { guard.postJournal(capture(requestSlot)) } returns Uni.createFrom().item(
            JournalResponse(id = UUID.randomUUID(), transactionId = UUID.randomUUID(), status = "POSTED"),
        )

        val result = adapter.post(posting()).await().indefinitely()

        assertThat(result).isEqualTo(Unit)
        val request = requestSlot.captured
        assertThat(request.idempotencyKey).isEqualTo("loan:42:disbursement")
        assertThat(request.entryDate).isEqualTo("2026-05-30")
        assertThat(request.valueDate).isEqualTo("2026-05-30")
        assertThat(request.createdBy).isEqualTo(actor)
        assertThat(request.lines).hasSize(2)
        assertThat(request.lines.map { it.glAccountId })
            .containsExactly(accounts.loansReceivable, accounts.fundingClearing)
        verify(exactly = 1) { guard.postJournal(any()) }
    }

    @Test
    fun `a ledger failure propagates instead of being swallowed`() {
        every { config.accounts() } returns accounts
        every { config.systemActorId() } returns actor
        every { guard.postJournal(any()) } returns Uni.createFrom().failure(IllegalStateException("ledger down"))

        assertThatThrownBy { adapter.post(posting(PostingKind.WRITE_OFF)).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ledger down")
    }

    @Test
    fun `config accounts() snapshots every GL leaf into the factory holder`() {
        val cfg = object : LendingLedgerConfig {
            override fun systemActorId(): UUID = actor
            override fun gl(): LendingLedgerConfig.Gl = object : LendingLedgerConfig.Gl {
                override fun loansReceivable(): UUID = accounts.loansReceivable
                override fun fundingClearing(): UUID = accounts.fundingClearing
                override fun interestIncome(): UUID = accounts.interestIncome
                override fun interestReceivable(): UUID = accounts.interestReceivable
                override fun loanLossExpense(): UUID = accounts.loanLossExpense
            }
        }

        assertThat(cfg.accounts()).isEqualTo(accounts)
    }
}
