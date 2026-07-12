// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.adapter

import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.infrastructure.client.JournalResponse
import com.openbank.settlement.infrastructure.client.LedgerRestClient
import com.openbank.settlement.infrastructure.client.PostJournalRequest
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Unit coverage for the ledger-api booking adapter, with a mocked REST client (Uni-wrapped). */
class LedgerBookAdapterTest {

    private val glDebitAccountId = UUID.randomUUID()
    private val glCreditAccountId = UUID.randomUUID()
    private val fixedClock = Clock.fixed(Instant.parse("2026-07-06T10:00:00Z"), ZoneOffset.UTC)

    private val settlement = Settlement(
        id = UUID.randomUUID(),
        payerAccountId = UUID.randomUUID(),
        payeeAccountId = UUID.randomUUID(),
        amount = BigDecimal("999.99"),
        currency = "CZK",
        status = SettlementStatus.CREDITED,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `book posts a balanced debit-credit journal dated from the injected clock`(): Unit = runBlocking {
        val ledgerClient = mockk<LedgerRestClient>()
        val repo = mockk<SettlementRepository>()
        coEvery { repo.findById(settlement.id) } returns settlement
        val request = slot<PostJournalRequest>()
        every { ledgerClient.postJournal(capture(request)) } returns
            Uni.createFrom().item(JournalResponse(UUID.randomUUID(), settlement.id, "POSTED"))

        LedgerBookAdapter(ledgerClient, repo, glDebitAccountId, glCreditAccountId, fixedClock).book(settlement.id)

        assertThat(request.captured.idempotencyKey).isEqualTo("settlement-book-${settlement.id}")
        assertThat(request.captured.transactionId).isEqualTo(settlement.id)
        assertThat(request.captured.entryDate).isEqualTo("2026-07-06")
        assertThat(request.captured.valueDate).isEqualTo("2026-07-06")
        assertThat(request.captured.createdBy).isEqualTo(LedgerBookAdapter.SYSTEM_USER)
        assertThat(request.captured.lines).hasSize(2)

        val debitLine = request.captured.lines.single { it.side == "DEBIT" }
        assertThat(debitLine.glAccountId).isEqualTo(glDebitAccountId)
        assertThat(debitLine.subAccountId).isEqualTo(settlement.payerAccountId)
        assertThat(debitLine.amount).isEqualByComparingTo("999.99")

        val creditLine = request.captured.lines.single { it.side == "CREDIT" }
        assertThat(creditLine.glAccountId).isEqualTo(glCreditAccountId)
        assertThat(creditLine.subAccountId).isEqualTo(settlement.payeeAccountId)
        assertThat(creditLine.amount).isEqualByComparingTo("999.99")

        verify { ledgerClient.postJournal(any()) }
    }

    @Test
    fun `book throws when the settlement is not found`(): Unit = runBlocking {
        val ledgerClient = mockk<LedgerRestClient>()
        val repo = mockk<SettlementRepository>()
        val missingId = UUID.randomUUID()
        coEvery { repo.findById(missingId) } returns null

        assertThatThrownBy {
            runBlocking {
                LedgerBookAdapter(ledgerClient, repo, glDebitAccountId, glCreditAccountId, fixedClock).book(missingId)
            }
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(missingId.toString())
    }

    @Test
    fun `book propagates a failure from the ledger client`(): Unit = runBlocking {
        val ledgerClient = mockk<LedgerRestClient>()
        val repo = mockk<SettlementRepository>()
        coEvery { repo.findById(settlement.id) } returns settlement
        every { ledgerClient.postJournal(any()) } returns
            Uni.createFrom().failure(RuntimeException("ledger-service down"))

        assertThatThrownBy {
            runBlocking {
                LedgerBookAdapter(
                    ledgerClient,
                    repo,
                    glDebitAccountId,
                    glCreditAccountId,
                    fixedClock,
                ).book(settlement.id)
            }
        }.isInstanceOf(RuntimeException::class.java).hasMessageContaining("ledger-service down")
    }
}
