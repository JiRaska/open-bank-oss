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
import com.openbank.settlement.application.port.out.SettlementMetricsPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.application.port.out.SettlementStep
import com.openbank.settlement.application.port.out.SettlementStepOutcome
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.temporal.failure.ApplicationFailure
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger
import java.time.Duration
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
// TooManyFunctions fires AT its threshold of 11, not above it. This class is the seven activities
// SettlementActivities declares — one method each, not decomposable — plus `compensate`, `audit`,
// `step` and `runOnVertxContext`. `cycleDuration` is already top-level for the same reason. Same
// rationale as CampaignJourneyActivitiesImpl, the fleet's other Temporal activities implementation.
@Suppress("TooManyFunctions")
open class SettlementActivitiesImpl(
    private val settlementRepository: SettlementRepository,
    private val debitPort: DebitPort,
    private val creditPort: CreditPort,
    private val ledgerPort: LedgerPort,
    private val auditPublisher: AuditEventPublisher,
    private val reverseDebitPort: ReverseDebitPort,
    private val reverseCreditPort: ReverseCreditPort,
    private val metrics: SettlementMetricsPort,
) : SettlementActivities {

    private val log = Logger.getLogger(SettlementActivitiesImpl::class.java)

    override fun debitPayer(settlementId: UUID): Unit = step(SettlementStep.DEBIT) {
        log.infof("Debiting payer for settlement %s", settlementId)
        debitPort.debit(settlementId)
        val settlement = settlementRepository.updateStatus(settlementId, SettlementStatus.DEBITED)
        audit("settlement.debit", settlement)
        Unit
    }

    override fun creditPayee(settlementId: UUID): Unit = step(SettlementStep.CREDIT) {
        log.infof("Crediting payee for settlement %s", settlementId)
        creditPort.credit(settlementId)
        val settlement = settlementRepository.updateStatus(settlementId, SettlementStatus.CREDITED)
        audit("settlement.credit", settlement)
        Unit
    }

    override fun bookToLedger(settlementId: UUID): Unit = step(SettlementStep.LEDGER_BOOK) {
        log.infof("Booking settlement %s to ledger", settlementId)
        ledgerPort.book(settlementId)
        val settlement = settlementRepository.updateStatus(settlementId, SettlementStatus.BOOKED)
        audit("settlement.ledger-book", settlement)
        // The saga's only success terminus. Recorded here, after the ledger write and the status
        // transition, so the counter can never claim a booking that did not happen.
        metrics.settlementBooked(settlement.currency, settlement.amount, settlement.cycleDuration())
    }

    override fun reverseDebit(settlementId: UUID): Unit = step(SettlementStep.REVERSE_DEBIT) {
        compensate(
            settlementId = settlementId,
            operation = "settlement.reverse-debit",
            onSuccess = SettlementStatus.REVERSED,
        ) { reverseDebitPort.reverseDebit(settlementId) }
    }

    override fun reverseCredit(settlementId: UUID): Unit = step(SettlementStep.REVERSE_CREDIT) {
        compensate(
            settlementId = settlementId,
            operation = "settlement.reverse-credit",
            onSuccess = SettlementStatus.CREDITED_REVERSED,
        ) { reverseCreditPort.reverseCredit(settlementId) }
    }

    /**
     * NOT IMPLEMENTED — fails loudly rather than reporting a reversal that did not happen.
     *
     * ledger-service does expose `POST /api/v1/journals/{journalId}/reverse`, but settlement-service
     * cannot use it as things stand, for three independent reasons, each needing a decision this
     * service cannot make on its own (issue #6037):
     *
     *  1. **Maker-checker.** `ledger.reverse` is an approval-gated action — a call from
     *     settlement-service's service account lands in ledger's PENDING approval queue rather than
     *     posting. Granting a machine that action is a `rules.yaml: shared_m2m_matrix_write_grants`
     *     decision, not a code change, and an *automatic* GL reversal driven by a failed saga is
     *     precisely the thing maker-checker exists to prevent.
     *  2. **No journal id is retained.** `bookToLedger` discards the `JournalResponse`, so the id
     *     would have to be persisted (a migration) or re-resolved via
     *     `GET /api/v1/journals/transaction/{transactionId}`.
     *  3. **Period locks.** The endpoint answers 409 when the original entry's fiscal period is
     *     ATTESTED, so a reversal is not always available and the saga needs a defined behaviour
     *     for that case — correcting forward in the open period is the accounting convention, which
     *     is a different posting from a reversal.
     *
     * Throwing a **non-retryable** failure is deliberate: a retryable one would burn all five
     * attempts (~75 s of backoff) delaying the two compensations that *do* work, and would still
     * end in the same place. `SettlementWorkflowImpl` catches `ActivityFailure` per compensation and
     * continues, so this failure does not block `reverseCredit`/`reverseDebit`.
     *
     * The status is recorded first, so the row says
     * [SettlementStatus.LEDGER_REVERSAL_UNSUPPORTED] — an operator sees *which* half of the unwind
     * did not happen — rather than the old `LEDGER_REVERSED`, which claimed it had.
     */
    override fun reverseBookToLedger(settlementId: UUID): Unit = step(SettlementStep.REVERSE_LEDGER_BOOK) {
        log.errorf(
            "Ledger booking for settlement %s was NOT reversed: settlement-service cannot reverse a " +
                "journal (ledger.reverse is maker-checker gated and no journal id is retained). " +
                "The GL still carries this settlement's posting and needs a manual correcting entry.",
            settlementId,
        )
        val settlement =
            settlementRepository.updateStatus(settlementId, SettlementStatus.LEDGER_REVERSAL_UNSUPPORTED)
        audit("settlement.reverse-ledger-book", settlement, result = AuditResult.FAILURE)
        throw ApplicationFailure.newNonRetryableFailure(
            "Ledger reversal is not implemented for settlement $settlementId; GL correction required",
            "LedgerReversalUnsupported",
        )
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

    override fun rejectSettlement(settlementId: UUID): Unit = step(SettlementStep.REJECT) {
        log.warnf("Rejecting settlement %s after compensation", settlementId)
        val settlement = settlementRepository.updateStatus(settlementId, SettlementStatus.REJECTED)
        audit("settlement.reject", settlement, result = AuditResult.FAILURE)
        metrics.settlementRejected(settlement.currency, settlement.cycleDuration())
    }

    /**
     * Run one activity [block] on a Vert.x context and record its attempt against [step].
     *
     * A failure is recorded and **rethrown**: Temporal owns the retry/compensation decision, so the
     * meter must not change the saga's behaviour — it only makes the attempt visible. `Throwable`
     * rather than `Exception` because a failure originating in native or static-initializer code
     * arrives as an `Error`, and an attempt that fails that way must not be counted as completed.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun step(step: SettlementStep, block: suspend () -> Unit): Unit = runOnVertxContext {
        try {
            block()
            metrics.sagaStep(step, SettlementStepOutcome.COMPLETED)
        } catch (ex: Throwable) {
            metrics.sagaStep(step, SettlementStepOutcome.FAILED)
            throw ex
        }
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

/**
 * Wall-clock duration of the settlement cycle, measured from the row's own audit columns.
 *
 * `updatedAt` is set by the repository on the status transition that just committed, so no clock is
 * needed here and the value cannot drift from what the database recorded. Clamped at zero by the
 * adapter.
 *
 * Top-level rather than a member: detekt's `TooManyFunctions` fires AT the threshold of 11, not
 * above it, and the seven activities plus `step`, `compensate`, `audit` and `runOnVertxContext`
 * already reach it. Declared **after** the class on purpose — a Kotlin annotation binds to the next
 * declaration, so a top-level function placed above an annotated class silently steals its
 * annotation (the `@Path`/McpEndpoint 404 this repo has already shipped once).
 */
private fun Settlement.cycleDuration(): Duration = Duration.between(createdAt, updatedAt)
