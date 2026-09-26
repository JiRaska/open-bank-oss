// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.LedgerBackfillRequestRepository
import com.openbank.lending.application.port.out.LedgerBackfillVoidRequestRepository
import com.openbank.lending.application.port.out.LedgerPostResult
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.infrastructure.persistence.entity.LedgerBackfillRequestEntity
import com.openbank.lending.infrastructure.persistence.entity.LedgerBackfillVoidRequestEntity
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.governance.MakerCheckerViolation
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** API view of one four-eyes void request (#10969). */
data class VoidRequestView(
    val id: UUID,
    val sourceRequestId: UUID,
    val state: ProposalState,
    val voidDate: LocalDate,
    val planHash: String,
    val loanCount: Int,
    val legCount: Int,
    val proposedBy: String,
    val decidedBy: String?,
    val decisionReason: String?,
    val executedBy: String?,
    val lastResult: String?,
    val proposedAt: OffsetDateTime? = null,
    val decidedAt: OffsetDateTime? = null,
    val executedAt: OffsetDateTime? = null,
)

/** Outcome of one leg: the original re-sent (normally a replay) and its mirror. */
data class VoidLegOutcome(
    val reference: String,
    val kind: String,
    val original: String,
    val mirror: String? = null,
    val error: String? = null,
)

/** Outcome for one loan: VOIDED (every leg offset, loan UNWOUND) or FAILED (stopped at the first failure). */
data class VoidLoanOutcome(val loanId: String, val status: String, val legs: List<VoidLegOutcome>)

/** Result of a void execute call: the plan when `execute=false`, the per-loan outcome when true. */
data class VoidExecution(
    val requestId: UUID,
    val executed: Boolean,
    val complete: Boolean,
    val plan: BackfillPlan,
    val loans: List<VoidLoanOutcome>,
)

/**
 * Four-eyes void of the synthetic loans a ledger backfill posted (#10969). The loans back-posted by
 * #10746 were never paid out, so they are cancelled, not disbursed.
 *
 * For every leg of every loan still ACTIVE in the source request's scope it first RE-SENDS the
 * original posting (its live idempotency reference: normally a replay that books nothing, and if the
 * ledger never had the leg it is booked now), then posts a MIRROR under `void:<reference>` with the
 * amount negated. Each leg therefore nets to exactly zero in the GL whatever the ledger held before,
 * and a re-run only replays. The loan then leaves the book as UNWOUND, with the same
 * `credit.loan.transition` evidence event every other status change emits.
 *
 * The status is set here rather than through the termination flow on purpose: UNWOUND there also
 * releases the loan-loss allowance, which would book a provision release on top of the mirrored
 * provisioning legs and leave a non-zero GL.
 *
 * A void always names an EXECUTED backfill request and re-plans that request's own scope, so it can
 * reach only the loans that request posted. Posts only through [LedgerPostingPort]; like the backfill,
 * it has no dependency on the borrower credit port, so it cannot move a customer's money.
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
class LedgerBackfillVoidService(
    private val book: LedgerBackfillBookReader,
    private val ledger: LedgerPostingPort,
    private val backfills: LedgerBackfillRequestRepository,
    private val voids: LedgerBackfillVoidRequestRepository,
    private val loans: LoanRepository,
    private val events: LoanEventEmitter,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(LedgerBackfillVoidService::class.java)
    private val json = ObjectMapper().findAndRegisterModules()

    /** Pure read: every leg that would be offset, for the loans of [sourceRequestId] still ACTIVE. */
    fun dryRun(sourceRequestId: UUID): Uni<BackfillPlan> = backfills.findById(sourceRequestId).flatMap { source ->
        requireNotNull(source) { "Backfill request not found: $sourceRequestId" }
        check(source.state == ProposalState.EXECUTED) {
            "backfill request $sourceRequestId is ${source.state}: only an EXECUTED backfill can be voided"
        }
        plan(source, AccountingClock.bank(clock).today())
    }

    fun list(limit: Int): Uni<List<VoidRequestView>> = voids.listRecent(limit).map { rows -> rows.map { it.toView() } }

    fun get(id: UUID): Uni<VoidRequestView?> = voids.findById(id).map { it?.toView() }

    fun propose(sourceRequestId: UUID, maker: String): Uni<VoidRequestView> {
        require(maker.isNotBlank()) { "Proposer identity is required" }
        return dryRun(sourceRequestId).flatMap { plan ->
            require(plan.executable) { "void plan is not executable: ${refusal(plan)}" }
            val now = OffsetDateTime.now(clock)
            val entity = LedgerBackfillVoidRequestEntity().apply {
                id = Ids.newId()
                this.sourceRequestId = sourceRequestId
                state = ProposalState.PROPOSED
                voidDate = plan.cutoverDate
                planHash = plan.planHash
                loanCount = plan.loans.size
                legCount = plan.legs.size
                proposedBy = maker
                proposedAt = now
                createdAt = now
                updatedAt = now
            }
            voids.findSignedOffByHash(plan.planHash).flatMap { signedOff ->
                signedOff.firstOrNull()?.let { error("this void is already ${it.state} by request ${it.id}") }
                voids.findProposedByHash(plan.planHash)
            }.flatMap { twin ->
                twin?.let { Uni.createFrom().item(it.toView()) }
                    ?: voids.save(entity).call { saved -> audit(saved, "PROPOSED", maker) }.map { it.toView() }
            }
        }
    }

    fun decide(id: UUID, approve: Boolean, checker: String, reason: String?): Uni<VoidRequestView> =
        voids.findById(id).flatMap { entity ->
            requireNotNull(entity) { "Void request not found: $id" }
            require(entity.state == ProposalState.PROPOSED) { "Void request $id is ${entity.state}, not decidable" }
            val proposal =
                Proposal(entity.id.toString(), entity.planHash, entity.proposedBy, entity.proposedAt.toInstant())
            val at = clock.instant()
            // Proposal.approve/reject throw MakerCheckerViolation when checker == maker — four-eyes in code.
            val decided = if (approve) proposal.approve(checker, at, reason) else proposal.reject(checker, at, reason)
            entity.state = decided.state
            entity.decidedBy = checker
            entity.decidedAt = OffsetDateTime.ofInstant(at, clock.zone)
            entity.decisionReason = reason
            entity.updatedAt = OffsetDateTime.now(clock)
            voids.compareAndSetDecision(entity).flatMap { claimed ->
                require(claimed == 1) { "Void request $id was decided concurrently and is no longer decidable" }
                audit(entity, decided.state.name, checker).map { entity.toView() }
            }
        }

    /**
     * Rebuild the plan for void [id]; with [execute] false return it, with [execute] true post it.
     * Posting requires an APPROVED void whose plan hash still matches (nothing moved since approval)
     * and a void date that has not passed.
     */
    fun execute(id: UUID, execute: Boolean, executor: String): Uni<VoidExecution> = voids.findById(id).flatMap { v ->
        requireNotNull(v) { "Void request not found: $id" }
        backfills.findById(v.sourceRequestId).flatMap { source ->
            checkNotNull(source) { "source backfill request ${v.sourceRequestId} is gone" }
            plan(source, v.voidDate).flatMap { plan ->
                if (!execute) {
                    Uni.createFrom().item(VoidExecution(id, false, false, plan, emptyList()))
                } else {
                    guardExecution(v, plan, executor)
                    runClaimed(v, source, plan, executor)
                }
            }
        }
    }

    /** The source's own scope, restricted to loans still ACTIVE: a voided loan is UNWOUND and drops out. */
    private fun plan(source: LedgerBackfillRequestEntity, voidDate: LocalDate): Uni<BackfillPlan> {
        val scope = BackfillScope(voidDate, disbursedBefore(source))
        return book.load(scope).map { rows ->
            val active = rows.filter { it.first.status == LoanStatus.ACTIVE }
            LedgerBackfillPlanner.plan(voidDate, active, LedgerBackfillService.BUSINESS_ZONE)
        }
    }

    private fun guardExecution(entity: LedgerBackfillVoidRequestEntity, plan: BackfillPlan, executor: String) {
        if (entity.state != ProposalState.APPROVED) {
            throw MakerCheckerViolation("Void request ${entity.id} is ${entity.state}: only an APPROVED void executes")
        }
        if (executor.isBlank()) throw MakerCheckerViolation("Executor identity is required")
        check(plan.planHash == entity.planHash) {
            "the book changed since approval (plan hash ${plan.planHash} != approved ${entity.planHash}); " +
                "propose and approve a new void"
        }
        check(plan.executable) { "void plan is no longer executable: ${refusal(plan)}" }
        check(!entity.voidDate.isBefore(AccountingClock.bank(clock).today())) {
            "voidDate ${entity.voidDate} has passed; propose a new void"
        }
    }

    private fun runClaimed(
        entity: LedgerBackfillVoidRequestEntity,
        source: LedgerBackfillRequestEntity,
        plan: BackfillPlan,
        executor: String,
    ): Uni<VoidExecution> {
        val now = OffsetDateTime.now(clock)
        return voids.claimExecution(entity.id, executor, now, now.minus(LedgerBackfillService.EXECUTION_LEASE))
            .flatMap { claimed ->
                check(claimed == 1) { "Void request ${entity.id} is already being executed" }
                audit(entity, "EXECUTION_STARTED", executor)
                    .flatMap {
                        Multi.createFrom().iterable(plan.loans)
                            .onItem().transformToUniAndConcatenate { loan ->
                                voidLoan(loan, source.cutoverDate, entity.voidDate, executor)
                            }
                            .collect().asList()
                    }
                    .flatMap { outcomes -> record(entity, executor, plan, outcomes) }
            }
    }

    private fun record(
        entity: LedgerBackfillVoidRequestEntity,
        executor: String,
        plan: BackfillPlan,
        outcomes: List<VoidLoanOutcome>,
    ): Uni<VoidExecution> {
        val complete = outcomes.none { it.status == FAILED }
        val legs = outcomes.flatMap { it.legs }
        val result = json.writeValueAsString(
            mapOf(
                "executedBy" to executor,
                "at" to OffsetDateTime.now(clock).toString(),
                "complete" to complete,
                "loansVoided" to outcomes.count { it.status == VOIDED },
                "loansFailed" to outcomes.filter { it.status == FAILED }.map { it.loanId },
                // Should be 0: an original the ledger did not have is booked now and offset at once.
                "originalsBookedNow" to legs.count { it.original == LedgerPostResult.POSTED.name },
                "mirrorsReplayed" to legs.count { it.mirror == LedgerPostResult.REPLAYED.name },
            ),
        )
        return voids.recordExecution(entity.id, result, complete, OffsetDateTime.now(clock))
            .flatMap { audit(entity, if (complete) "EXECUTED" else "EXECUTION_PARTIAL", executor) }
            .map { VoidExecution(entity.id, executed = true, complete = complete, plan = plan, loans = outcomes) }
    }

    /** Offset every leg in order, stopping at the first failure; only a fully offset loan becomes UNWOUND. */
    private fun voidLoan(
        loan: LoanBackfillPlan,
        originalDate: LocalDate,
        voidDate: LocalDate,
        executor: String,
    ): Uni<VoidLoanOutcome> = loans.findById(LoanId(UUID.fromString(loan.loanId)))
        .flatMap { domain ->
            checkNotNull(domain) { "loan ${loan.loanId} is gone" }
            var failed = false
            Multi.createFrom().iterable(loan.legs)
                .onItem().transformToUniAndConcatenate { leg ->
                    if (failed) {
                        Uni.createFrom().item(VoidLegOutcome(leg.reference, leg.kind.name, SKIPPED))
                    } else {
                        offsetLeg(leg, domain, originalDate, voidDate).onFailure().recoverWithItem { e ->
                            failed = true
                            log.warnf("backfill void: %s failed for %s: %s", leg.kind, leg.reference, e.message)
                            VoidLegOutcome(leg.reference, leg.kind.name, FAILED, error = e.message)
                        }
                    }
                }
                .collect().asList()
                .flatMap { legs ->
                    if (legs.any { it.original == FAILED || it.original == SKIPPED }) {
                        Uni.createFrom().item(VoidLoanOutcome(loan.loanId, FAILED, legs))
                    } else {
                        unwind(domain, executor).map { VoidLoanOutcome(loan.loanId, VOIDED, legs) }
                    }
                }
        }

    private fun offsetLeg(leg: BackfillLeg, loan: Loan, originalDate: LocalDate, voidDate: LocalDate) =
        ledger.postReportingReplay(LedgerBackfillPlanner.toPosting(leg, loan.partyId, originalDate))
            .flatMap { original ->
                ledger.postReportingReplay(mirror(leg, loan, voidDate)).map { mirror ->
                    VoidLegOutcome(leg.reference, leg.kind.name, original.name, mirror.name)
                }
            }

    private fun mirror(leg: BackfillLeg, loan: Loan, voidDate: LocalDate) = LedgerPosting(
        reference = "$MIRROR_PREFIX${leg.reference}",
        partyId = loan.partyId,
        amount = Money.of(leg.amount.negate(), leg.currency),
        kind = leg.kind,
        accountingDate = voidDate,
        valueDate = voidDate,
    )

    private fun unwind(loan: Loan, executor: String): Uni<Unit> = loans.withLocked(loan.id) { current ->
        checkNotNull(current) { "loan ${loan.id.value} is gone" }
        if (current.status == LoanStatus.UNWOUND) {
            Uni.createFrom().item(Unit)
        } else {
            check(current.status == LoanStatus.ACTIVE) {
                "loan ${loan.id.value} is ${current.status}; only an ACTIVE synthetic loan is voided"
            }
            loans.update(current.copy(status = LoanStatus.UNWOUND))
                .call { _ -> events.emit(evidence(current, executor)) }
                .map { }
        }
    }

    private fun evidence(loan: Loan, actor: String): LendingOutboxMessage {
        val id = loan.id.value
        val payload = json.writeValueAsString(
            linkedMapOf(
                "eventType" to "credit.loan.transition",
                "aggregateType" to "LOAN",
                "aggregateId" to id.toString(),
                "loanId" to id.toString(),
                "partyId" to loan.partyId.toString(),
                "fromState" to loan.status.name,
                "toState" to LoanStatus.UNWOUND.name,
                "actorId" to actor,
                "reason" to VOID_REASON,
                "occurredAt" to clock.instant().toString(),
                "correlationId" to id.toString(),
                "sourceService" to "lending",
            ),
        )
        return LendingOutboxMessage(aggregateId = id, eventType = "credit.loan.transition", payload = payload)
    }

    /** Audit trail on the lending topic (audit-service consumes it). Carries no party data. */
    private fun audit(entity: LedgerBackfillVoidRequestEntity, transition: String, actor: String): Uni<Unit> =
        events.emit(
            LendingOutboxMessage(
                aggregateId = entity.id,
                eventType = "lending.ledger_backfill_void.transition",
                payload = json.writeValueAsString(
                    linkedMapOf(
                        "aggregateType" to "LEDGER_BACKFILL_VOID",
                        "aggregateId" to entity.id.toString(),
                        "sourceRequestId" to entity.sourceRequestId.toString(),
                        "transition" to transition,
                        "actor" to actor,
                        "planHash" to entity.planHash,
                        "voidDate" to entity.voidDate.toString(),
                        "loanCount" to entity.loanCount,
                        "legCount" to entity.legCount,
                        "occurredAt" to clock.instant().toString(),
                        "sourceService" to "lending",
                    ),
                ),
            ),
        )

    companion object {
        const val VOIDED = "VOIDED"
        const val FAILED = "FAILED"
        const val SKIPPED = "SKIPPED"

        /** Idempotency prefix of a mirror journal: `void:<original reference>`. */
        const val MIRROR_PREFIX = "void:"
        const val VOID_REASON = "synthetic loan never paid out: voided with every ledger leg offset (#10969)"
    }
}

private fun refusal(plan: BackfillPlan): String = when {
    plan.legs.isEmpty() -> "no ACTIVE loans left in the source request's scope"
    plan.unsupported.isNotEmpty() ->
        "${plan.unsupported.size} loan(s) unsupported: " +
            plan.unsupported.joinToString { "${it.loanId} (${it.unsupportedReason})" }
    else -> "tie-out fails: " + plan.tieOut.filterNot { it.ties }
        .joinToString { "${it.currency} ${it.loansReceivableAfter} != ${it.lendingUnpaidPrincipal}" }
}

private fun LedgerBackfillVoidRequestEntity.toView() = VoidRequestView(
    id = id,
    sourceRequestId = sourceRequestId,
    state = state,
    voidDate = voidDate,
    planHash = planHash,
    loanCount = loanCount,
    legCount = legCount,
    proposedBy = proposedBy,
    decidedBy = decidedBy,
    decisionReason = decisionReason,
    executedBy = executedBy,
    lastResult = lastResult,
    proposedAt = proposedAt,
    decidedAt = decidedAt,
    executedAt = executedAt,
)

private fun disbursedBefore(source: LedgerBackfillRequestEntity): LocalDate = LocalDate.parse(
    ObjectMapper().readTree(
        checkNotNull(source.lastResult) { "backfill request ${source.id} has no scope" },
    ).get("disbursedBefore").asText(),
)
