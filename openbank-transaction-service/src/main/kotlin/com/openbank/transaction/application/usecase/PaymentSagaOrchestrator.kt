// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.application.usecase

import com.openbank.transaction.application.port.out.BalanceCoverPort
import com.openbank.transaction.application.port.out.PaymentSagaRepository
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.saga.PaymentSaga
import com.openbank.transaction.domain.saga.SagaState
import com.openbank.transaction.infrastructure.client.LedgerCallGuard
import com.openbank.transaction.infrastructure.client.PostJournalRequest
import com.openbank.transaction.infrastructure.client.ReverseJournalRequest
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.util.UUID

@ApplicationScoped
class PaymentSagaOrchestrator(
    private val sagaRepository: PaymentSagaRepository,
    private val ledgerCallGuard: LedgerCallGuard,
    private val balanceCoverPort: BalanceCoverPort,
    private val clock: Clock,
) {

    @Inject
    constructor(
        sagaRepository: PaymentSagaRepository,
        ledgerCallGuard: LedgerCallGuard,
        balanceCoverPort: BalanceCoverPort,
    ) : this(sagaRepository, ledgerCallGuard, balanceCoverPort, Clock.systemUTC())
    private val log = Logger.getLogger(PaymentSagaOrchestrator::class.java)

    private val systemActor = UUID.fromString("00000000-0000-0000-0000-000000000001")

    // Safety net: if releasing a hold ever fails, balance-service expires it after this TTL so a
    // reservation can never leak indefinitely.
    private val holdTtlSeconds = 300L

    suspend fun startSaga(transaction: Transaction): PaymentSaga {
        val existing = sagaRepository.findByIdempotencyKey(transaction.idempotencyKey)
        if (existing != null) return existing

        val saga = PaymentSaga.start(transaction.id, transaction.idempotencyKey, clock)
        val saved = sagaRepository.save(saga)
        return executeSteps(saved, transaction)
    }

    // ADR-0039 Phase D-2: the ledger is the golden source of the booked balance and balance-service
    // projects it, so the saga no longer moves booked directly. It (1) places a synchronous cover hold
    // on the source pocket (the overdraft-aware overspend gate) and (2) posts the ledger journal. The
    // booked movement AND the cover-hold release then land asynchronously in balance-service as it
    // projects the journal's AccountBookedChanged event (release keyed by referenceId == transactionId),
    // which closes the overspend window without the saga debiting balance. Incoming credits (no source
    // account) skip the hold. holdId/journalId stay local because the saga runs synchronously.
    private suspend fun executeSteps(saga: PaymentSaga, transaction: Transaction): PaymentSaga {
        var current = sagaRepository.update(saga.transitionTo(SagaState.PAYMENT_INITIATED, clock))
        val coverAccount = transaction.sourceAccountId
        val cover = transaction.baseAmount
        var holdId: UUID? = null
        var journalId: UUID? = null

        return try {
            if (coverAccount != null) {
                current = sagaRepository.update(current.transitionTo(SagaState.FUNDS_RESERVED, clock))
                holdId = balanceCoverPort.placeHold(
                    accountId = coverAccount,
                    amount = cover.amount,
                    currency = cover.currency.code,
                    reason = "payment ${transaction.id}",
                    referenceId = transaction.id.toString(),
                    ttlSeconds = holdTtlSeconds,
                )
            }

            current = sagaRepository.update(current.transitionTo(SagaState.LEDGER_POSTING, clock))
            journalId = ledgerCallGuard.postJournal(buildJournalRequest(current, transaction)).awaitSuspending().id

            // No direct balance debit/credit and no success-path hold release: the ledger projection in
            // balance-service is the sole booked-mover and releases the cover hold as the booked delta
            // lands. Releasing the hold here would reopen the overspend window the projection closes.
            sagaRepository.update(current.transitionTo(SagaState.COMPLETED, clock))
        } catch (ex: Exception) {
            log.errorf(ex, "Payment saga %s failed, starting compensation", current.id)
            compensate(
                saga = current,
                holdId = holdId,
                journalId = journalId,
                reason = ex.message ?: ex.javaClass.simpleName,
            )
        }
    }

    private fun buildJournalRequest(saga: PaymentSaga, transaction: Transaction) = PostJournalRequest(
        idempotencyKey = "saga-${saga.id}-ledger",
        transactionId = transaction.id,
        entryDate = transaction.bookingDate.toString(),
        valueDate = transaction.valueDate.toString(),
        description = transaction.description,
        lines = PaymentJournalFactory.buildLines(transaction),
        createdBy = systemActor,
    )

    // Unwind whatever side effects landed before the failure: reverse a committed journal (the post
    // succeeded but the final transition threw) and release the standing hold. Both are best-effort —
    // the ledger reversal is idempotent and the hold has a TTL — so a compensation failure still
    // records COMPENSATED rather than leaking the saga. No booked refund is needed: the saga never
    // debited balance (ADR-0039 Phase D-2), so reversing the journal emits a negated
    // AccountBookedChanged that the projection applies to restore the booked balance. If the journal
    // never posted, the projection never fires, so releasing the hold here (or its TTL) is the only
    // thing that frees the reservation; if it did post, the projection already released the hold and
    // this release is a harmless idempotent no-op.
    private suspend fun compensate(saga: PaymentSaga, holdId: UUID?, journalId: UUID?, reason: String): PaymentSaga {
        val compensating = sagaRepository.update(saga.startCompensation(reason, clock))
        if (journalId != null) {
            try {
                ledgerCallGuard.reverseJournal(
                    journalId,
                    ReverseJournalRequest(reason = "compensation: $reason", reversedBy = systemActor),
                ).awaitSuspending()
            } catch (ex: Exception) {
                log.errorf(ex, "Failed to reverse journal %s during compensation of saga %s", journalId, saga.id)
            }
        }
        releaseQuietly(holdId)
        return sagaRepository.update(compensating.compensated(clock))
    }

    private suspend fun releaseQuietly(holdId: UUID?) {
        if (holdId == null) return
        try {
            balanceCoverPort.releaseHold(holdId)
        } catch (ex: Exception) {
            log.warnf(ex, "Failed to release hold %s; relying on balance-service TTL", holdId)
        }
    }
}
