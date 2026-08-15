// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import com.openbank.libs.domain.money.Money
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.transaction.application.port.out.BalanceCoverPort
import com.openbank.transaction.application.port.out.TransactionEventPublisher
import com.openbank.transaction.application.port.out.TransactionRepository
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.infrastructure.client.JournalResponse
import com.openbank.transaction.infrastructure.client.LedgerCallGuard
import com.openbank.transaction.infrastructure.client.PostJournalRequest
import com.openbank.transaction.infrastructure.client.ReverseJournalRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PaymentActivitiesImplTest {

    private val transactionRepository: TransactionRepository = mockk()
    private val ledgerCallGuard: LedgerCallGuard = mockk()
    private val balanceCoverPort: BalanceCoverPort = mockk()
    private val eventPublisher: TransactionEventPublisher = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC)

    private lateinit var activities: PaymentActivitiesImpl

    @BeforeEach
    fun setUp() {
        activities = TestableActivities(
            transactionRepository,
            ledgerCallGuard,
            balanceCoverPort,
            eventPublisher,
            clock,
        )
    }

    @Test
    fun `markCompleted writes COMPLETED and the completed outbox message in one call`(): Unit = runBlocking {
        val tx = transaction(sourceAccountId = UUID.randomUUID())
        coEvery { transactionRepository.findById(tx.id) } returns tx
        every { eventPublisher.completedPayload(any()) } returns "{\"event\":\"completed\"}"
        coEvery { transactionRepository.update(any(), any()) } answers { firstArg() }

        activities.markCompleted(tx.id)

        coVerify {
            transactionRepository.update(
                match { it.id == tx.id && it.status == TransactionStatus.COMPLETED && it.completedAt != null },
                match<OutboxMessage> {
                    it.eventType == "openbank.transactions.transaction.completed" && it.aggregateId == tx.id
                },
            )
        }
    }

    @Test
    fun `markCompleted is a no-op on an already COMPLETED row (at-least-once replay)`(): Unit = runBlocking {
        // Temporal re-runs an activity whose completion never reached the workflow history. A second
        // update would fail the version check and enqueue a duplicate completed event.
        val tx = transaction(sourceAccountId = UUID.randomUUID())
            .copy(status = TransactionStatus.COMPLETED, completedAt = Instant.parse("2026-06-28T09:00:00Z"))
        coEvery { transactionRepository.findById(tx.id) } returns tx

        activities.markCompleted(tx.id)

        coVerify(exactly = 0) { transactionRepository.update(any(), any()) }
    }

    @Test
    fun `markFailed writes FAILED and the failed outbox message`(): Unit = runBlocking {
        val tx = transaction(sourceAccountId = UUID.randomUUID())
        coEvery { transactionRepository.findById(tx.id) } returns tx
        every { eventPublisher.failedPayload(any(), any()) } returns "{\"event\":\"failed\"}"
        coEvery { transactionRepository.update(any(), any()) } answers { firstArg() }

        activities.markFailed(tx.id, "Payment workflow did not complete (state=COMPENSATED)")

        coVerify {
            transactionRepository.update(
                match {
                    it.status == TransactionStatus.FAILED &&
                        it.failureReason?.contains("COMPENSATED") == true
                },
                match<OutboxMessage> { it.eventType == "openbank.transactions.transaction.failed" },
            )
        }
    }

    @Test
    fun `markFailed refuses to touch a COMPLETED row`(): Unit = runBlocking {
        val tx = transaction(sourceAccountId = UUID.randomUUID())
            .copy(status = TransactionStatus.COMPLETED, completedAt = Instant.parse("2026-06-28T09:00:00Z"))
        coEvery { transactionRepository.findById(tx.id) } returns tx

        // No throw (which would make Temporal retry forever) and no write.
        activities.markFailed(tx.id, "late compensation")

        coVerify(exactly = 0) { transactionRepository.update(any(), any()) }
    }

    @Test
    fun `placeHold delegates to balance cover port with orchestrator-identical args`(): Unit = runBlocking {
        val sourceId = UUID.randomUUID()
        val tx = transaction(sourceAccountId = sourceId)
        val holdId = UUID.randomUUID()
        coEvery { transactionRepository.findById(tx.id) } returns tx
        coEvery {
            balanceCoverPort.placeHold(any(), any(), any(), any(), any(), any())
        } returns holdId

        val result = activities.placeHold(tx.id)

        assertThat(result).isEqualTo(holdId)
        coVerify {
            balanceCoverPort.placeHold(
                accountId = sourceId,
                amount = tx.baseAmount.amount,
                currency = tx.baseAmount.currency.code,
                reason = "payment ${tx.id}",
                referenceId = tx.id.toString(),
                ttlSeconds = 300L,
            )
        }
    }

    @Test
    fun `placeHold returns sentinel and skips port when no source account`(): Unit = runBlocking {
        val tx = transaction(sourceAccountId = null)
        coEvery { transactionRepository.findById(tx.id) } returns tx

        val result = activities.placeHold(tx.id)

        assertThat(result).isEqualTo(PaymentActivities.SENTINEL_HOLD)
        coVerify(exactly = 0) { balanceCoverPort.placeHold(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `postJournal posts via ledger guard and returns journal id`(): Unit = runBlocking {
        val tx = transaction(sourceAccountId = UUID.randomUUID())
        val journalId = UUID.randomUUID()
        coEvery { transactionRepository.findById(tx.id) } returns tx
        val requestSlot = slot<PostJournalRequest>()
        every { ledgerCallGuard.postJournal(capture(requestSlot)) } returns
            Uni.createFrom().item(JournalResponse(journalId, tx.id, "POSTED"))

        val result = activities.postJournal(tx.id)

        assertThat(result).isEqualTo(journalId)
        assertThat(requestSlot.captured.transactionId).isEqualTo(tx.id)
        assertThat(requestSlot.captured.idempotencyKey).isEqualTo("workflow-${tx.id}-ledger")
    }

    @Test
    fun `releaseHold delegates to balance cover port`(): Unit = runBlocking {
        val holdId = UUID.randomUUID()
        coEvery { balanceCoverPort.releaseHold(holdId) } returns Unit

        activities.releaseHold(holdId)

        coVerify { balanceCoverPort.releaseHold(holdId) }
    }

    @Test
    fun `releaseHold ignores the sentinel hold`(): Unit = runBlocking {
        activities.releaseHold(PaymentActivities.SENTINEL_HOLD)
        coVerify(exactly = 0) { balanceCoverPort.releaseHold(any()) }
    }

    @Test
    fun `releaseHold swallows port exceptions`(): Unit = runBlocking {
        val holdId = UUID.randomUUID()
        coEvery { balanceCoverPort.releaseHold(holdId) } throws RuntimeException("boom")

        // No exception propagates — quiet best-effort compensation.
        activities.releaseHold(holdId)

        coVerify { balanceCoverPort.releaseHold(holdId) }
    }

    @Test
    fun `reverseJournal delegates to ledger guard`(): Unit = runBlocking {
        val journalId = UUID.randomUUID()
        val requestSlot = slot<ReverseJournalRequest>()
        every { ledgerCallGuard.reverseJournal(journalId, capture(requestSlot)) } returns
            Uni.createFrom().item(JournalResponse(journalId, UUID.randomUUID(), "REVERSED"))

        activities.reverseJournal(journalId)

        coVerify { ledgerCallGuard.reverseJournal(journalId, any()) }
        assertThat(requestSlot.captured.reason).contains("compensation")
    }

    @Test
    fun `reverseJournal swallows ledger exceptions`(): Unit = runBlocking {
        val journalId = UUID.randomUUID()
        every { ledgerCallGuard.reverseJournal(journalId, any()) } throws RuntimeException("boom")

        // No exception propagates — quiet best-effort compensation.
        activities.reverseJournal(journalId)

        coVerify { ledgerCallGuard.reverseJournal(journalId, any()) }
    }

    private fun transaction(sourceAccountId: UUID?): Transaction = Transaction(
        id = UUID.randomUUID(),
        referenceNumber = "TXN202606280001",
        type = TransactionType.TRANSFER,
        sourceAccountId = sourceAccountId,
        targetAccountId = if (sourceAccountId == null) UUID.randomUUID() else null,
        amount = Money.of(BigDecimal("100.00"), "CZK"),
        fxRate = null,
        baseAmount = Money.of(BigDecimal("100.00"), "CZK"),
        status = TransactionStatus.PENDING,
        description = "test payment",
        valueDate = LocalDate.of(2026, 6, 28),
        bookingDate = LocalDate.of(2026, 6, 28),
        initiatedAt = Instant.parse("2026-06-28T00:00:00Z"),
        completedAt = null,
        failedAt = null,
        failureReason = null,
        idempotencyKey = "idem-${UUID.randomUUID()}",
        version = 0L,
    )
}

/** Runs the activity bodies synchronously, bypassing the real Vert.x-context bridge. */
private class TestableActivities(
    transactionRepository: TransactionRepository,
    ledgerCallGuard: LedgerCallGuard,
    balanceCoverPort: BalanceCoverPort,
    eventPublisher: TransactionEventPublisher,
    clock: Clock,
) : PaymentActivitiesImpl(transactionRepository, ledgerCallGuard, balanceCoverPort, eventPublisher, clock) {
    override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
}
