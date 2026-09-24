// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.LedgerBackfillRequestRepository
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.infrastructure.persistence.entity.LedgerBackfillRequestEntity
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.governance.MakerCheckerViolation
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** The scope of a backfill: which loans, and the accounting day the journals are booked into. */
data class BackfillScope(val cutoverDate: LocalDate, val disbursedBefore: LocalDate)

/** API view of one four-eyes request. */
data class BackfillRequestView(
    val id: UUID,
    val state: ProposalState,
    val cutoverDate: LocalDate,
    val planHash: String,
    val loanCount: Int,
    val legCount: Int,
    val proposedBy: String,
    val decidedBy: String?,
    val decisionReason: String?,
    val executedBy: String?,
    val lastResult: String?,
)

/** Outcome of one leg in an execution run. */
data class LegOutcome(val reference: String, val kind: String, val status: String, val error: String? = null)

/** Outcome for one loan: POSTED (every leg accepted by the ledger), FAILED (stopped at the first failure). */
data class LoanOutcome(
    val loanId: String,
    val status: String,
    val legsPosted: Int,
    val legsTotal: Int,
    val legs: List<LegOutcome>,
)

/** Result of an execute call: the dry-run plan when `execute=false`, the per-loan outcome when true. */
data class BackfillExecution(
    val requestId: UUID,
    val executed: Boolean,
    val complete: Boolean,
    val plan: BackfillPlan,
    val loans: List<LoanOutcome>,
)

/**
 * One-off, four-eyes ledger backfill for loans whose GL history never reached the ledger (#10746,
 * root cause #6057). Dry-run by default; posting needs an APPROVED request (approver != proposer)
 * whose plan hash still matches the book, and an explicit `execute=true`.
 *
 * Posts ONLY through [LedgerPostingPort] — the ledger REST API, with the live idempotency references
 * — and never touches the borrower credit port: this class has no dependency on it, by construction.
 */
@ApplicationScoped
class LedgerBackfillService(
    private val book: LedgerBackfillBookReader,
    private val ledger: LedgerPostingPort,
    private val requests: LedgerBackfillRequestRepository,
    private val events: LoanEventEmitter,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(LedgerBackfillService::class.java)
    private val json = ObjectMapper().findAndRegisterModules()

    /** Pure read: the exact journal set, per-GL-relevant totals and the tie-out. Writes nothing anywhere. */
    fun dryRun(scope: BackfillScope): Uni<BackfillPlan> {
        val today = LocalDate.now(clock.withZone(BUSINESS_ZONE))
        require(!scope.cutoverDate.isBefore(today)) {
            "cutoverDate ${scope.cutoverDate} is before today ($today): the backfill books into the current " +
                "open accounting day and never back-dates into closed days — original dates go in valueDate"
        }
        return book.load(scope).map { book -> LedgerBackfillPlanner.plan(scope.cutoverDate, book, BUSINESS_ZONE) }
    }

    fun propose(scope: BackfillScope, maker: String): Uni<BackfillRequestView> {
        require(maker.isNotBlank()) { "Proposer identity is required" }
        return dryRun(scope).flatMap { plan ->
            require(plan.executable) { "plan is not executable: ${refusal(plan)}" }
            val now = OffsetDateTime.now(clock)
            val entity = LedgerBackfillRequestEntity().apply {
                id = Ids.newId()
                state = ProposalState.PROPOSED
                cutoverDate = scope.cutoverDate
                planHash = plan.planHash
                loanCount = plan.loans.size
                legCount = plan.legs.size
                proposedBy = maker
                proposedAt = now
                // The disbursement scope is part of what was approved: kept in the result column
                // until the first run so execute() can rebuild exactly this plan.
                lastResult = json.writeValueAsString(mapOf("disbursedBefore" to scope.disbursedBefore.toString()))
                createdAt = now
                updatedAt = now
            }
            requests.save(entity).call { saved -> audit(saved, "PROPOSED", maker) }.map { it.toView() }
        }
    }

    fun decide(id: UUID, approve: Boolean, checker: String, reason: String?): Uni<BackfillRequestView> =
        requests.findById(id).flatMap { entity ->
            requireNotNull(entity) { "Backfill request not found: $id" }
            require(entity.state == ProposalState.PROPOSED) { "Backfill request $id is ${entity.state}, not decidable" }
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
            requests.compareAndSetDecision(entity).flatMap { claimed ->
                require(claimed == 1) { "Backfill request $id was decided concurrently and is no longer decidable" }
                audit(entity, decided.state.name, checker).map { entity.toView() }
            }
        }

    /**
     * Rebuild the plan for request [id]; with [execute] false return it (dry-run), with [execute] true
     * post it. Posting requires an APPROVED request (so a second person has signed off), a plan hash
     * identical to the approved one (the book has not moved since), and a cut-over date not yet passed.
     */
    fun execute(id: UUID, execute: Boolean, executor: String): Uni<BackfillExecution> =
        requests.findById(id).flatMap { entity ->
            requireNotNull(entity) { "Backfill request not found: $id" }
            val scope = BackfillScope(entity.cutoverDate, disbursedBefore(entity))
            book.load(scope).flatMap { book ->
                val plan = LedgerBackfillPlanner.plan(scope.cutoverDate, book, BUSINESS_ZONE)
                if (!execute) {
                    Uni.createFrom().item(
                        BackfillExecution(id, executed = false, complete = false, plan = plan, loans = emptyList()),
                    )
                } else {
                    guardExecution(entity, plan, executor)
                    runClaimed(entity, plan, book.associate { it.first.id.value.toString() to it.first }, executor)
                }
            }
        }

    private fun guardExecution(entity: LedgerBackfillRequestEntity, plan: BackfillPlan, executor: String) {
        if (entity.state != ProposalState.APPROVED) {
            throw MakerCheckerViolation(
                "Backfill request ${entity.id} is ${entity.state}: only an APPROVED request executes",
            )
        }
        if (executor.isBlank()) throw MakerCheckerViolation("Executor identity is required")
        check(plan.planHash == entity.planHash) {
            "the book changed since approval (plan hash ${plan.planHash} != approved ${entity.planHash}); " +
                "propose and approve a new request"
        }
        check(plan.executable) { "plan is no longer executable: ${refusal(plan)}" }
        val today = LocalDate.now(clock.withZone(BUSINESS_ZONE))
        check(!entity.cutoverDate.isBefore(today)) {
            "cutoverDate ${entity.cutoverDate} has passed; propose a new request with a current cut-over date"
        }
    }

    private fun runClaimed(
        entity: LedgerBackfillRequestEntity,
        plan: BackfillPlan,
        loansById: Map<String, Loan>,
        executor: String,
    ): Uni<BackfillExecution> {
        val now = OffsetDateTime.now(clock)
        return requests.claimExecution(entity.id, executor, now, now.minus(EXECUTION_LEASE)).flatMap { claimed ->
            check(claimed == 1) { "Backfill request ${entity.id} is already being executed" }
            audit(entity, "EXECUTION_STARTED", executor)
                .flatMap {
                    Multi.createFrom().iterable(plan.loans)
                        .onItem().transformToUniAndConcatenate { loan ->
                            postLoan(loan, loansById.getValue(loan.loanId), plan.cutoverDate)
                        }
                        .collect().asList()
                }
                .flatMap { outcomes ->
                    val complete = outcomes.all { it.status == POSTED }
                    val result = json.writeValueAsString(
                        mapOf(
                            "disbursedBefore" to disbursedBefore(entity).toString(),
                            "executedBy" to executor,
                            "at" to OffsetDateTime.now(clock).toString(),
                            "complete" to complete,
                            "loansPosted" to outcomes.count { it.status == POSTED },
                            "loansFailed" to outcomes.filter { it.status != POSTED }.map { it.loanId },
                        ),
                    )
                    requests.recordExecution(entity.id, result, complete, OffsetDateTime.now(clock))
                        .flatMap { audit(entity, if (complete) "EXECUTED" else "EXECUTION_PARTIAL", executor) }
                        .map {
                            BackfillExecution(
                                entity.id,
                                executed = true,
                                complete = complete,
                                plan = plan,
                                loans = outcomes,
                            )
                        }
                }
        }
    }

    /** Post one loan's legs in order; stop at the first failure (later legs depend on earlier ones). */
    private fun postLoan(loan: LoanBackfillPlan, domain: Loan, cutover: LocalDate): Uni<LoanOutcome> {
        var failed = false
        return Multi.createFrom().iterable(loan.legs)
            .onItem().transformToUniAndConcatenate { leg ->
                if (failed) {
                    Uni.createFrom().item(LegOutcome(leg.reference, leg.kind.name, SKIPPED))
                } else {
                    ledger.post(LedgerBackfillPlanner.toPosting(leg, domain.partyId, cutover))
                        .map { LegOutcome(leg.reference, leg.kind.name, POSTED) }
                        .onFailure().recoverWithItem { e ->
                            failed = true
                            log.warnf("ledger backfill: %s failed for %s: %s", leg.kind, leg.reference, e.message)
                            LegOutcome(leg.reference, leg.kind.name, FAILED, e.message)
                        }
                }
            }
            .collect().asList()
            .map { outcomes ->
                val posted = outcomes.count { it.status == POSTED }
                LoanOutcome(
                    loan.loanId,
                    if (posted ==
                        outcomes.size
                    ) {
                        POSTED
                    } else {
                        FAILED
                    },
                    posted,
                    outcomes.size,
                    outcomes,
                )
            }
    }

    /** Audit trail on the lending topic (audit-service consumes it). Carries no party data. */
    private fun audit(entity: LedgerBackfillRequestEntity, transition: String, actor: String): Uni<Unit> = events.emit(
        LendingOutboxMessage(
            aggregateId = entity.id,
            eventType = "lending.ledger_backfill.transition",
            payload = json.writeValueAsString(
                linkedMapOf(
                    "aggregateType" to "LEDGER_BACKFILL",
                    "aggregateId" to entity.id.toString(),
                    "transition" to transition,
                    "actor" to actor,
                    "planHash" to entity.planHash,
                    "cutoverDate" to entity.cutoverDate.toString(),
                    "loanCount" to entity.loanCount,
                    "legCount" to entity.legCount,
                    "occurredAt" to clock.instant().toString(),
                    "sourceService" to "lending",
                ),
            ),
        ),
    )

    companion object {
        /** The ledger's accounting day is a Prague business day (AccountingDayScheduler). */
        val BUSINESS_ZONE: ZoneId = ZoneId.of("Europe/Prague")
        const val MAX_LOANS = 10_000
        val EXECUTION_LEASE: Duration = Duration.ofMinutes(15)
        const val POSTED = "POSTED"
        const val FAILED = "FAILED"
        const val SKIPPED = "SKIPPED"
    }
}

private fun refusal(plan: BackfillPlan): String = when {
    plan.legs.isEmpty() -> "no loans in scope"
    plan.unsupported.isNotEmpty() ->
        "${plan.unsupported.size} loan(s) unsupported: " +
            plan.unsupported.joinToString { "${it.loanId} (${it.unsupportedReason})" }
    else -> "tie-out fails: " + plan.tieOut.filterNot { it.ties }
        .joinToString { "${it.currency} ${it.loansReceivableAfter} != ${it.lendingUnpaidPrincipal}" }
}

private fun LedgerBackfillRequestEntity.toView() = BackfillRequestView(
    id = id,
    state = state,
    cutoverDate = cutoverDate,
    planHash = planHash,
    loanCount = loanCount,
    legCount = legCount,
    proposedBy = proposedBy,
    decidedBy = decidedBy,
    decisionReason = decisionReason,
    executedBy = executedBy,
    lastResult = lastResult,
)

private fun disbursedBefore(entity: LedgerBackfillRequestEntity): LocalDate = LocalDate.parse(
    SCOPE_JSON.readTree(
        checkNotNull(entity.lastResult) {
            "request ${entity.id} has no scope"
        },
    ).get("disbursedBefore").asText(),
)

private val SCOPE_JSON = ObjectMapper()
