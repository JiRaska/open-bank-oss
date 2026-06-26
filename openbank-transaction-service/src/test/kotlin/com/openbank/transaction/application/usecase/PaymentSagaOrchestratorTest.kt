// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.application.usecase

import com.openbank.libs.domain.money.Money
import com.openbank.transaction.application.port.out.BalanceCoverPort
import com.openbank.transaction.application.port.out.PaymentSagaRepository
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.domain.saga.PaymentSaga
import com.openbank.transaction.domain.saga.SagaState
import com.openbank.transaction.infrastructure.client.JournalResponse
import com.openbank.transaction.infrastructure.client.LedgerCallGuard
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ADR-0039 Phase D-2: the saga reserves a cover hold and posts the ledger journal; it no longer moves
// the booked balance (no debit/credit) — balance-service projects the ledger event and releases the
// matching hold. These tests pin that the saga drives the right HTTP side effects per direction and
// that compensation unwinds (reverse journal + release hold) without a booked refund.
class PaymentSagaOrchestratorTest {
    private lateinit var sagaRepository: PaymentSagaRepository
    private lateinit var ledgerCallGuard: LedgerCallGuard
    private lateinit var balanceCoverPort: BalanceCoverPort
    private lateinit var orchestrator: PaymentSagaOrchestrator

    @BeforeEach
    fun setUp() {
        sagaRepository = mockk()
        ledgerCallGuard = mockk()
        balanceCoverPort = mockk()
        orchestrator = PaymentSagaOrchestrator(sagaRepository, ledgerCallGuard, balanceCoverPort, Clock.systemUTC())

        coEvery { sagaRepository.findByIdempotencyKey(any()) } returns null
        coEvery { sagaRepository.save(any()) } answers { firstArg() }
        coEvery { sagaRepository.update(any()) } answers { firstArg() }
    }

    @Test
    fun `outgoing payment reserves the cover, posts the journal and completes without moving booked`(): Unit =
        runBlocking {
            // External beneficiary: source pocket only, no internal target.
            val transaction = transaction(sourceAccountId = UUID.randomUUID(), targetAccountId = null)
            val holdId = UUID.randomUUID()
            coEvery { balanceCoverPort.placeHold(any(), any(), any(), any(), any(), any()) } returns holdId
            every { ledgerCallGuard.postJournal(any()) } returns
                Uni.createFrom().item(JournalResponse(UUID.randomUUID(), transaction.id, "POSTED"))

            val result = orchestrator.startSaga(transaction)

            assertThat(result.state).isEqualTo(SagaState.COMPLETED)
            coVerify {
                balanceCoverPort.placeHold(
                    transaction.sourceAccountId!!,
                    transaction.baseAmount.amount,
                    transaction.baseAmount.currency.code,
                    any(),
                    transaction.id.toString(),
                    any(),
                )
                ledgerCallGuard.postJournal(any())
            }
            // The cover hold is released by the ledger projection as the booked delta lands, NOT here —
            // releasing on success would reopen the overspend window the projection closes.
            coVerify(exactly = 0) { balanceCoverPort.releaseHold(any()) }
        }

    @Test
    fun `incoming credit without source account skips the cover hold and completes`(): Unit = runBlocking {
        val transaction = transaction(sourceAccountId = null)
        every { ledgerCallGuard.postJournal(any()) } returns
            Uni.createFrom().item(JournalResponse(UUID.randomUUID(), transaction.id, "POSTED"))

        val result = orchestrator.startSaga(transaction)

        assertThat(result.state).isEqualTo(SagaState.COMPLETED)
        coVerify { ledgerCallGuard.postJournal(any()) }
        coVerify(exactly = 0) {
            balanceCoverPort.placeHold(any(), any(), any(), any(), any(), any())
            balanceCoverPort.releaseHold(any())
        }
    }

    @Test
    fun `internal transfer reserves on the source, posts the journal and completes`(): Unit = runBlocking {
        val transaction = transaction(sourceAccountId = UUID.randomUUID(), targetAccountId = UUID.randomUUID())
        val holdId = UUID.randomUUID()
        coEvery { balanceCoverPort.placeHold(any(), any(), any(), any(), any(), any()) } returns holdId
        every { ledgerCallGuard.postJournal(any()) } returns
            Uni.createFrom().item(JournalResponse(UUID.randomUUID(), transaction.id, "POSTED"))

        val result = orchestrator.startSaga(transaction)

        assertThat(result.state).isEqualTo(SagaState.COMPLETED)
        coVerify {
            balanceCoverPort.placeHold(
                transaction.sourceAccountId!!,
                transaction.baseAmount.amount,
                transaction.baseAmount.currency.code,
                any(),
                transaction.id.toString(),
                any(),
            )
            ledgerCallGuard.postJournal(any())
        }
        coVerify(exactly = 0) { balanceCoverPort.releaseHold(any()) }
    }

    @Test
    fun `ledger failure after a hold releases the hold and compensates without reversal`(): Unit = runBlocking {
        val transaction = transaction(sourceAccountId = UUID.randomUUID())
        val holdId = UUID.randomUUID()
        coEvery { balanceCoverPort.placeHold(any(), any(), any(), any(), any(), any()) } returns holdId
        coEvery { balanceCoverPort.releaseHold(holdId) } just Runs
        every { ledgerCallGuard.postJournal(any()) } returns
            Uni.createFrom().failure(RuntimeException("ledger boom"))

        val result = orchestrator.startSaga(transaction)

        assertThat(result.state).isEqualTo(SagaState.COMPENSATED)
        coVerify { balanceCoverPort.releaseHold(holdId) }
        coVerify(exactly = 0) { ledgerCallGuard.reverseJournal(any(), any()) }
    }

    @Test
    fun `failure after a committed posting reverses the journal and releases the hold`(): Unit = runBlocking {
        // The ledger posted, then the final COMPLETED transition threw. Compensation must reverse the
        // committed journal (its negated AccountBookedChanged restores the projected booked balance)
        // and release the standing hold — no separate booked refund (the saga never debited balance).
        val transaction = transaction(sourceAccountId = UUID.randomUUID())
        val holdId = UUID.randomUUID()
        val journalId = UUID.randomUUID()
        coEvery { balanceCoverPort.placeHold(any(), any(), any(), any(), any(), any()) } returns holdId
        coEvery { balanceCoverPort.releaseHold(holdId) } just Runs
        coEvery { sagaRepository.update(match { it.state == SagaState.COMPLETED }) } throws
            RuntimeException("commit boom")
        every { ledgerCallGuard.postJournal(any()) } returns
            Uni.createFrom().item(JournalResponse(journalId, transaction.id, "POSTED"))
        every { ledgerCallGuard.reverseJournal(eq(journalId), any()) } returns
            Uni.createFrom().item(JournalResponse(UUID.randomUUID(), transaction.id, "REVERSED"))

        val result = orchestrator.startSaga(transaction)

        assertThat(result.state).isEqualTo(SagaState.COMPENSATED)
        coVerify {
            ledgerCallGuard.reverseJournal(eq(journalId), any())
            balanceCoverPort.releaseHold(holdId)
        }
    }

    @Test
    fun `compensation still records COMPENSATED when the journal reversal itself fails`(): Unit = runBlocking {
        // Best-effort unwind: the committed journal's reversal ALSO fails. The saga must still land in
        // COMPENSATED rather than leak — the ledger reversal is idempotent and retried out of band.
        val transaction = transaction(sourceAccountId = UUID.randomUUID())
        val holdId = UUID.randomUUID()
        val journalId = UUID.randomUUID()
        coEvery { balanceCoverPort.placeHold(any(), any(), any(), any(), any(), any()) } returns holdId
        coEvery { balanceCoverPort.releaseHold(holdId) } just Runs
        coEvery { sagaRepository.update(match { it.state == SagaState.COMPLETED }) } throws
            RuntimeException("commit boom")
        every { ledgerCallGuard.postJournal(any()) } returns
            Uni.createFrom().item(JournalResponse(journalId, transaction.id, "POSTED"))
        every { ledgerCallGuard.reverseJournal(eq(journalId), any()) } returns
            Uni.createFrom().failure(RuntimeException("reverse boom"))

        val result = orchestrator.startSaga(transaction)

        assertThat(result.state).isEqualTo(SagaState.COMPENSATED)
        coVerify {
            ledgerCallGuard.reverseJournal(eq(journalId), any())
            balanceCoverPort.releaseHold(holdId)
        }
    }

    @Test
    fun `compensation still records COMPENSATED when releasing the hold fails`(): Unit = runBlocking {
        // The ledger post failed (→ compensation, no journal to reverse) and releasing the standing
        // hold throws. The saga must still land in COMPENSATED; balance-service expires the hold via
        // its TTL so the reservation cannot leak.
        val transaction = transaction(sourceAccountId = UUID.randomUUID())
        val holdId = UUID.randomUUID()
        coEvery { balanceCoverPort.placeHold(any(), any(), any(), any(), any(), any()) } returns holdId
        coEvery { balanceCoverPort.releaseHold(holdId) } throws RuntimeException("release boom")
        every { ledgerCallGuard.postJournal(any()) } returns
            Uni.createFrom().failure(RuntimeException("ledger boom"))

        val result = orchestrator.startSaga(transaction)

        assertThat(result.state).isEqualTo(SagaState.COMPENSATED)
        coVerify { balanceCoverPort.releaseHold(holdId) }
        coVerify(exactly = 0) { ledgerCallGuard.reverseJournal(any(), any()) }
    }

    @Test
    fun `a duplicate idempotency key returns the existing saga without re-running any steps`(): Unit = runBlocking {
        // Idempotent replay: a second startSaga for the same key must short-circuit to the stored
        // saga and touch neither the ledger nor the balance pockets (no double spend).
        val transaction = transaction(sourceAccountId = UUID.randomUUID())
        val existing = PaymentSaga.start(transaction.id, transaction.idempotencyKey, Clock.systemUTC())
        coEvery { sagaRepository.findByIdempotencyKey(transaction.idempotencyKey) } returns existing

        val result = orchestrator.startSaga(transaction)

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 0) {
            sagaRepository.save(any())
            balanceCoverPort.placeHold(any(), any(), any(), any(), any(), any())
            ledgerCallGuard.postJournal(any())
        }
    }

    private fun transaction(sourceAccountId: UUID?, targetAccountId: UUID? = UUID.randomUUID()) = Transaction(
        id = UUID.randomUUID(),
        referenceNumber = "TXN202606010001",
        type = TransactionType.TRANSFER,
        sourceAccountId = sourceAccountId,
        targetAccountId = targetAccountId,
        amount = Money.of(BigDecimal("1250.50"), "CZK"),
        fxRate = null,
        baseAmount = Money.of(BigDecimal("1250.50"), "CZK"),
        status = TransactionStatus.PENDING,
        description = "Invoice settlement",
        valueDate = LocalDate.of(2026, 6, 1),
        bookingDate = LocalDate.of(2026, 6, 1),
        initiatedAt = Instant.now(),
        completedAt = null,
        failedAt = null,
        failureReason = null,
        idempotencyKey = "idem-${UUID.randomUUID()}",
        version = 0L,
    )
}
