// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.transaction.application.port.out.BalanceCoverPort
import com.openbank.transaction.application.port.out.TransactionEventPublisher
import com.openbank.transaction.application.port.out.TransactionRepository
import com.openbank.transaction.application.usecase.PaymentJournalFactory
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.infrastructure.client.LedgerCallGuard
import com.openbank.transaction.infrastructure.client.PostJournalRequest
import com.openbank.transaction.infrastructure.client.ReverseJournalRequest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger
import java.time.Clock
import java.util.UUID

/**
 * Temporal activity implementations for the ADR-0120 P1 payment workflow.
 *
 * Each activity delegates to the SAME ports `PaymentSagaOrchestrator` uses, with identical arguments,
 * so the Temporal path is behaviour-equivalent to the legacy saga. The activity loads the
 * [Transaction] by id (the workflow only carries the id, keeping the Temporal payload small and
 * serialisable) and reproduces the orchestrator's port calls verbatim. Compensation activities swallow
 * exceptions with a log, mirroring the orchestrator's quiet best-effort compensation.
 *
 * `open` so [PaymentActivitiesImplTest] can override [runOnVertxContext] to run synchronously.
 */
@ApplicationScoped
open class PaymentActivitiesImpl(
    private val transactionRepository: TransactionRepository,
    private val ledgerCallGuard: LedgerCallGuard,
    private val balanceCoverPort: BalanceCoverPort,
    private val eventPublisher: TransactionEventPublisher,
    private val clock: Clock,
) : PaymentActivities {

    private val log = Logger.getLogger(PaymentActivitiesImpl::class.java)

    private val systemActor = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private companion object {
        // Safety net: if releasing a hold ever fails, balance-service expires it after this TTL so a
        // reservation can never leak indefinitely. Identical to PaymentSagaOrchestrator.holdTtlSeconds.
        const val HOLD_TTL_SECONDS = 300L

        const val TRANSACTION_COMPLETED_EVENT = "openbank.transactions.transaction.completed"
        const val TRANSACTION_FAILED_EVENT = "openbank.transactions.transaction.failed"
    }

    override fun placeHold(transactionId: UUID): UUID = runOnVertxContext {
        val transaction = loadTransaction(transactionId)
        val coverAccount = transaction.sourceAccountId
        if (coverAccount == null) {
            // Incoming credit: no source pocket to reserve. Return the sentinel so the workflow knows
            // there is nothing to release on compensation.
            log.infof("Transaction %s has no source account; skipping cover hold", transactionId)
            return@runOnVertxContext PaymentActivities.SENTINEL_HOLD
        }
        val cover = transaction.baseAmount
        balanceCoverPort.placeHold(
            accountId = coverAccount,
            amount = cover.amount,
            currency = cover.currency.code,
            reason = "payment ${transaction.id}",
            referenceId = transaction.id.toString(),
            ttlSeconds = HOLD_TTL_SECONDS,
        )
    }

    override fun postJournal(transactionId: UUID): UUID = runOnVertxContext {
        val transaction = loadTransaction(transactionId)
        ledgerCallGuard.postJournal(buildJournalRequest(transaction)).awaitSuspending().id
    }

    // Best-effort compensation: balance-service TTL-expires the hold and the ledger reversal is
    // idempotent, so any failure here is logged and swallowed (mirrors PaymentSagaOrchestrator's quiet
    // compensation). The broad catch is deliberate — every failure mode degrades to the same recovery.
    @Suppress("TooGenericExceptionCaught")
    override fun releaseHold(holdId: UUID): Unit = runOnVertxContext {
        if (holdId == PaymentActivities.SENTINEL_HOLD) return@runOnVertxContext
        try {
            balanceCoverPort.releaseHold(holdId)
        } catch (ex: Exception) {
            log.warnf(ex, "Failed to release hold %s; relying on balance-service TTL", holdId)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun reverseJournal(journalId: UUID): Unit = runOnVertxContext {
        try {
            ledgerCallGuard.reverseJournal(
                journalId,
                ReverseJournalRequest(reason = "compensation: payment workflow", reversedBy = systemActor),
            ).awaitSuspending()
        } catch (ex: Exception) {
            log.errorf(ex, "Failed to reverse journal %s during workflow compensation", journalId)
        }
    }

    override fun markCompleted(transactionId: UUID): Unit = runOnVertxContext {
        val transaction = loadTransaction(transactionId)
        // At-least-once replay: Temporal may re-run this activity after the commit but before the
        // completion was recorded on the workflow. A row already COMPLETED is the intended end
        // state, so return without a second update (which would also fail the version check) and
        // without a duplicate outbox row.
        if (transaction.status == TransactionStatus.COMPLETED) {
            log.debugf("Transaction %s already COMPLETED; markCompleted replay is a no-op", transactionId)
            return@runOnVertxContext
        }
        val completed = transaction.complete(clock)
        transactionRepository.update(
            transaction = completed,
            outboxMessage = OutboxMessage(
                aggregateId = completed.id,
                eventType = TRANSACTION_COMPLETED_EVENT,
                payload = eventPublisher.completedPayload(completed),
            ),
        )
    }

    override fun markFailed(transactionId: UUID, reason: String): Unit = runOnVertxContext {
        val transaction = loadTransaction(transactionId)
        if (transaction.status == TransactionStatus.FAILED) {
            log.debugf("Transaction %s already FAILED; markFailed replay is a no-op", transactionId)
            return@runOnVertxContext
        }
        // A COMPLETED row cannot be failed (Transaction.fail rejects it). That combination means the
        // workflow compensated after the terminal write already landed — log rather than throw, so a
        // retry storm cannot be created out of a state that is already final.
        if (transaction.status == TransactionStatus.COMPLETED) {
            log.warnf("Transaction %s is COMPLETED; refusing to mark it FAILED (%s)", transactionId, reason)
            return@runOnVertxContext
        }
        val failed = transaction.fail(reason, clock)
        transactionRepository.update(
            transaction = failed,
            outboxMessage = OutboxMessage(
                aggregateId = failed.id,
                eventType = TRANSACTION_FAILED_EVENT,
                payload = eventPublisher.failedPayload(failed, reason),
            ),
        )
    }

    private suspend fun loadTransaction(transactionId: UUID): Transaction =
        transactionRepository.findById(transactionId)
            ?: error("Transaction $transactionId not found for payment workflow")

    private fun buildJournalRequest(transaction: Transaction) = PostJournalRequest(
        idempotencyKey = "workflow-${transaction.id}-ledger",
        transactionId = transaction.id,
        entryDate = transaction.bookingDate.toString(),
        valueDate = transaction.valueDate.toString(),
        description = transaction.description,
        lines = PaymentJournalFactory.buildLines(transaction),
        createdBy = systemActor,
    )

    /**
     * Run a reactive (Hibernate Reactive / Mutiny) suspend [block] on a Vert.x duplicated context.
     *
     * Temporal activity methods run on a Temporal worker thread with **no** current Vert.x context, so a
     * naive `runBlocking { panache.withSession { ... } }` fails with `IllegalStateException: No current
     * Vertx context found` and the reactive Panache session cannot open.
     * [VertxContextSupport.subscribeAndAwait] establishes a fresh duplicated context, subscribes the
     * resulting Uni on it and blocks the worker thread until it completes — the activity-thread
     * equivalent of a request thread's ambient context. Mirrors `SettlementActivitiesImpl`.
     * `protected open` so tests can override it to run synchronously.
     */
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
