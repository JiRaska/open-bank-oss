// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.workflow

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.settlement.application.port.out.CreditPort
import com.openbank.settlement.application.port.out.DebitPort
import com.openbank.settlement.application.port.out.LedgerJournalLookupPort
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
// LongParameterList fires AT its threshold of 9, not above it, and #6410's LedgerJournalLookupPort
// is the ninth. The fleet's usual answer — field injection — does not apply here: this is a Temporal
// activities impl that the unit tests construct directly, and MetricsTestableActivities subclasses it
// through this constructor, so moving a port to `@Inject lateinit var` would break both test doubles
// for a lint threshold. Suppressed rather than restructured; revisit if a tenth port appears.
@Suppress("TooManyFunctions", "LongParameterList")
open class SettlementActivitiesImpl(
    private val settlementRepository: SettlementRepository,
    private val debitPort: DebitPort,
    private val creditPort: CreditPort,
    private val ledgerPort: LedgerPort,
    private val auditPublisher: AuditEventPublisher,
    private val reverseDebitPort: ReverseDebitPort,
    private val reverseCreditPort: ReverseCreditPort,
    private val metrics: SettlementMetricsPort,
    private val ledgerJournalLookupPort: LedgerJournalLookupPort,
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
     * Compensates the ledger booking by first establishing **what the general ledger actually
     * holds**, then recording that fact. Three outcomes, three statuses (issue #6410).
     *
     * ### Why it asks the ledger instead of assuming
     *
     * `bookToLedger` posts the journal and *then* writes `BOOKED`; either half can fail alone, so
     * a thrown `bookToLedger` says nothing about whether a journal exists. Both readings are
     * common and they call for opposite responses. Assuming the worst — what the previous
     * unconditional `LEDGER_REVERSAL_UNSUPPORTED` did — sends an accountant to correct an entry
     * that, in the ordinary "ledger refused the posting" case, was never made; and noise on the
     * one control that exists to make a real GL discrepancy visible is what hides a real GL
     * discrepancy. So the activity asks: `GET /api/v1/journals/transaction/{settlementId}`, keyed
     * on the `transactionId` [SettlementJournalFactory] already posts.
     *
     * That lookup also retires one of the three blockers this method's original KDoc listed. "No
     * journal id is retained" does not hold — the settlement id **is** the handle. The other two
     * stand and are why nothing here reverses anything:
     *
     *  1. **Four-eyes.** `reverse` is a `rules.yaml: four_eyes.verbs` verb, so `ledger.reverse`
     *     carries `four_eyes_required`. That flag is computed from the action name alone, with no
     *     awareness of the caller, and `rules.yaml`' own guardrail on that list says adding an
     *     automated caller to a four-eyes verb pauses the automation indistinguishably from the
     *     human path it was meant to gate. Making a failed saga post a GL reversal by itself is
     *     precisely what dual control exists to prevent; it is a governance decision, not a code
     *     change.
     *  2. **Period locks.** `POST /journals/{id}/reverse` answers 409 when the original entry's
     *     fiscal year is ATTESTED. Correcting forward in the open period is the accounting
     *     convention there, and that is a *different posting* from a reversal — a decision this
     *     service cannot take on a settlement's behalf.
     *
     * ### The three outcomes
     *
     *  - **A journal exists** — the GL carries this settlement and owes a correcting entry.
     *    [SettlementStatus.LEDGER_REVERSAL_UNSUPPORTED], and a **non-retryable** failure: no retry
     *    changes either blocker above, and burning five attempts (~75 s of backoff) would only
     *    delay the rest of the unwind.
     *  - **No journal exists** — nothing was posted, so there is nothing to reverse and no
     *    obligation to report. [SettlementStatus.LEDGER_NOT_POSTED], and the activity **returns
     *    normally**, so the workflow can go on to reject the settlement cleanly. This is a no-op
     *    outcome with its own value rather than a success flag shared with a real reversal.
     *  - **The lookup failed** — the honest answer is that nobody knows.
     *    [SettlementStatus.LEDGER_STATE_UNKNOWN], and a **retryable** failure: an unreachable
     *    ledger is the one case here that a retry can genuinely resolve.
     *
     * `SettlementWorkflowImpl` catches `ActivityFailure` per compensation and continues, so
     * neither failure blocks `reverseCredit`/`reverseDebit`.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun reverseBookToLedger(settlementId: UUID): Unit = step(SettlementStep.REVERSE_LEDGER_BOOK) {
        val posted = try {
            ledgerJournalLookupPort.countJournalsForSettlement(settlementId) > 0
        } catch (ex: Exception) {
            log.errorf(
                ex,
                "Could not establish whether settlement %s reached the general ledger; the ledger " +
                    "journal lookup failed. The GL state is UNKNOWN — it is not safe to report " +
                    "either a clean ledger or a standing posting.",
                settlementId,
            )
            val unknown =
                settlementRepository.updateStatus(settlementId, SettlementStatus.LEDGER_STATE_UNKNOWN)
            audit("settlement.reverse-ledger-book", unknown, result = AuditResult.FAILURE)
            throw ApplicationFailure.newFailure(
                "Ledger state for settlement $settlementId could not be established",
                "LedgerStateUnknown",
            )
        }

        if (!posted) {
            log.infof(
                "Ledger holds no journal for settlement %s, so the booking never posted and there " +
                    "is nothing to reverse; the general ledger is clean.",
                settlementId,
            )
            val clean = settlementRepository.updateStatus(settlementId, SettlementStatus.LEDGER_NOT_POSTED)
            audit("settlement.reverse-ledger-book", clean)
            return@step
        }
        log.errorf(
            "Ledger booking for settlement %s was NOT reversed: a journal exists for it and " +
                "settlement-service cannot reverse one (ledger.reverse is a four-eyes verb, and a " +
                "reversal into an ATTESTED period is refused). The GL still carries this " +
                "settlement's posting and needs a manual correcting entry.",
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
