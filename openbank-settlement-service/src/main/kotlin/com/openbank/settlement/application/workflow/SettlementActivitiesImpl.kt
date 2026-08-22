// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.workflow

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.settlement.application.port.out.CreditPort
import com.openbank.settlement.application.port.out.DebitPort
import com.openbank.settlement.application.port.out.LedgerPort
import com.openbank.settlement.application.port.out.ReverseCreditPort
import com.openbank.settlement.application.port.out.ReverseDebitPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger
import java.util.UUID

/**
 * ADR-0101 P3 settlement saga activities. Each state transition below also emits an [AuditEvent]
 * onto the shared libs audit pipeline (issue #1502 — settlement-service previously emitted zero
 * audit events despite governance.yaml claiming a `topic` edge to audit-service, a DORA Art. 17
 * reconstructability gap for a debit→credit→ledger-booking money-path saga). Mirrors the pattern
 * `PartyResource` uses in openbank-party-service: inject the libs [AuditEventPublisher] directly
 * (the `@Default` [com.openbank.libs.audit.LoggingAuditEventPublisher] bean satisfies it — no
 * Kafka topic/config/gitops wiring required), publish *after* the mutation + status update
 * succeed so a thrown activity exception (Temporal will retry) never records a false SUCCESS.
 */
@ApplicationScoped
open class SettlementActivitiesImpl(
    private val settlementRepository: SettlementRepository,
    private val debitPort: DebitPort,
    private val creditPort: CreditPort,
    private val ledgerPort: LedgerPort,
    private val auditPublisher: AuditEventPublisher,
    private val reverseDebitPort: ReverseDebitPort,
    private val reverseCreditPort: ReverseCreditPort,
) : SettlementActivities {

    private val log = Logger.getLogger(SettlementActivitiesImpl::class.java)

    override fun debitPayer(settlementId: UUID): Unit = runOnVertxContext {
        log.infof("Debiting payer for settlement %s", settlementId)
        debitPort.debit(settlementId)
        val settlement = settlementRepository.updateStatus(settlementId, SettlementStatus.DEBITED)
        audit("settlement.debit", settlement)
        Unit
    }

    override fun creditPayee(settlementId: UUID): Unit = runOnVertxContext {
        log.infof("Crediting payee for settlement %s", settlementId)
        creditPort.credit(settlementId)
        val settlement = settlementRepository.updateStatus(settlementId, SettlementStatus.CREDITED)
        audit("settlement.credit", settlement)
        Unit
    }

    /**
     * Books the settlement to the general ledger. This is the LAST forward step, and it writes the
     * terminal [SettlementStatus.BOOKED] itself — so nothing can fail after it and this posting has
     * no compensation (issue #6410, which removed the `reverseBookToLedger` activity that could
     * never run).
     *
     * Kept here because it is what a future step after this one would have to solve. Reversing a
     * GL posting from this service is not a code change; three separate decisions block it
     * (issue #6037):
     *
     *  1. **Maker-checker.** `ledger.reverse` is approval-gated — a call from settlement-service's
     *     service account lands in ledger's PENDING queue rather than posting. Granting a machine
     *     that action is a `rules.yaml: shared_m2m_matrix_write_grants` decision, and an
     *     *automatic* GL reversal driven by a failed saga is what maker-checker exists to prevent.
     *  2. **No journal id is retained.** This method discards the `JournalResponse`, so the id
     *     would have to be persisted (a migration) or re-resolved via
     *     `GET /api/v1/journals/transaction/{transactionId}`.
     *  3. **Period locks.** The endpoint answers 409 when the original entry's fiscal period is
     *     ATTESTED, so a reversal is not always available; correcting forward in the open period is
     *     the accounting convention, and that is a different posting.
     *
     * So a step added after this one needs an answer for the GL before it is added, not after.
     */
    override fun bookToLedger(settlementId: UUID): Unit = runOnVertxContext {
        log.infof("Booking settlement %s to ledger", settlementId)
        ledgerPort.book(settlementId)
        val settlement = settlementRepository.updateStatus(settlementId, SettlementStatus.BOOKED)
        audit("settlement.ledger-book", settlement)
        Unit
    }

    override fun reverseDebit(settlementId: UUID): Unit = runOnVertxContext {
        compensate(
            settlementId = settlementId,
            operation = "settlement.reverse-debit",
            onSuccess = SettlementStatus.REVERSED,
        ) { reverseDebitPort.reverseDebit(settlementId) }
    }

    override fun reverseCredit(settlementId: UUID): Unit = runOnVertxContext {
        compensate(
            settlementId = settlementId,
            operation = "settlement.reverse-credit",
            onSuccess = SettlementStatus.CREDITED_REVERSED,
        ) { reverseCreditPort.reverseCredit(settlementId) }
    }

    /**
     * Runs one balance-service compensation and records **what actually happened**.
     *
     * Order matters and mirrors the forward activities: the money call goes first, and the status
     * is written only once it has returned. A status written before the call — which is all the
     * pre-#6037 stubs did — is a claim the code has not yet earned.
     *
     * On failure the row is moved to [SettlementStatus.REVERSAL_FAILED] (money still moved), a
     * FAILURE audit event is published, and the exception is **rethrown** so Temporal retries and
     * the workflow sees the failure. A later successful attempt overwrites the status with the real
     * outcome, so `REVERSAL_FAILED` is the resting state only when every attempt failed.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun compensate(
        settlementId: UUID,
        operation: String,
        onSuccess: SettlementStatus,
        movement: suspend () -> Unit,
    ) {
        try {
            movement()
        } catch (ex: Throwable) {
            log.errorf(
                ex,
                "Compensation %s FAILED for settlement %s — the money has NOT been returned",
                operation,
                settlementId,
            )
            val failed = settlementRepository.updateStatus(settlementId, SettlementStatus.REVERSAL_FAILED)
            audit(operation, failed, result = AuditResult.FAILURE)
            throw ex
        }
        val settlement = settlementRepository.updateStatus(settlementId, onSuccess)
        audit(operation, settlement)
    }

    override fun rejectSettlement(settlementId: UUID): Unit = runOnVertxContext {
        log.warnf("Rejecting settlement %s after compensation", settlementId)
        val settlement = settlementRepository.updateStatus(settlementId, SettlementStatus.REJECTED)
        audit("settlement.reject", settlement, result = AuditResult.FAILURE)
        Unit
    }

    /**
     * Publishes an [AuditEvent] for a settlement-saga state transition. `actorId`/`actorType` are
     * `settlement-service`/`SERVICE` (not a JWT subject): Temporal activities run on a worker
     * thread with no caller identity — the saga itself is the actor, same convention as
     * party-service's M2M-caller audit entries. Payload carries amount/currency/payer/payee so the
     * event is reconstructable on its own (Art. 17), without a join back to the settlements table.
     */
    private suspend fun audit(operation: String, settlement: Settlement, result: AuditResult = AuditResult.SUCCESS) {
        auditPublisher.publish(
            AuditEvent(
                actorId = "settlement-service",
                actorType = "SERVICE",
                operation = operation,
                resourceType = "settlement",
                resourceId = settlement.id.toString(),
                result = result,
                payload = mapOf(
                    "status" to settlement.status.name,
                    "payerAccountId" to settlement.payerAccountId.toString(),
                    "payeeAccountId" to settlement.payeeAccountId.toString(),
                    "amount" to settlement.amount.toString(),
                    "currency" to settlement.currency,
                ),
            ),
        )
    }

    /**
     * Run a reactive (Hibernate Reactive / Mutiny) suspend [block] on a Vert.x duplicated context.
     *
     * Temporal activity methods run on a Temporal worker thread with **no** current Vert.x context, so a
     * naive `runBlocking { panache.withSession { ... } }` fails with `IllegalStateException: No current
     * Vertx context found` and the reactive Panache session cannot open. (Surfaced by the ADR-0101 P3
     * settlement go-live e2e: the settlement workflow REJECTED because every activity's repo access threw.)
     *
     * [VertxContextSupport.subscribeAndAwait] establishes a fresh duplicated context, subscribes the
     * resulting Uni on it and blocks the worker thread until it completes — the activity-thread equivalent
     * of a request thread's ambient context. Mirrors `SepaPaymentActivitiesImpl.runOnVertxContext`.
     * `protected open` so tests can override it to run synchronously.
     */
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
