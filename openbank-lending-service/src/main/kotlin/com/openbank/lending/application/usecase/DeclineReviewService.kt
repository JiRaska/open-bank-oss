// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.DeclineReviewRepository
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.OriginationWorkflowPort
import com.openbank.lending.application.port.out.TimerArmingOutcome
import com.openbank.lending.domain.model.DeclineReview
import com.openbank.lending.domain.model.DeclineReviewDecision
import com.openbank.lending.domain.model.DeclineReviewDecisionRequest
import com.openbank.lending.domain.model.DeclineReviewStatus
import com.openbank.lending.domain.model.FirstDueDate
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanRateType
import com.openbank.lending.infrastructure.compliance.CompliancePackGuard
import com.openbank.lending.infrastructure.compliance.OriginationConfig
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.lending.origination.OriginationActorKind
import com.openbank.libs.lending.origination.OriginationState
import com.openbank.libs.lending.origination.OriginationStateMachine
import com.openbank.libs.lending.origination.OriginationTransition
import com.openbank.libs.lending.origination.OriginationTransitionResult
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Customer-requested human review of a decision-engine decline.
 *
 * The declined application stays DECLINED, which is terminal in the shared origination graph
 * (ADR-0211 D1) — nothing here touches that graph. The review is its own record. OVERTURNED opens a
 * SUCCESSOR application that enters FOUR_EYES with the reviewer recorded as proposer, so the existing
 * four-eyes `decide` (approver != proposer) still makes the credit decision; the reviewer cannot also
 * approve it, and the engine outcome binds neither of them. CONFIRMED just records the human decision.
 *
 * Events carry identifiers and machine codes only; the reviewer's free-text reason stays in the
 * review row and is never published.
 *
 * The successor does NOT pass back through intake. It re-runs intake's refusal gates (origination
 * allowed for the product, the FLOATING-rate switch, rate terms against a recomputed first due date)
 * and pins the compliance pack active today, but it keeps the declined application's rate and catalog
 * snapshot as they were: there is no re-pricing step here, and no fresh engine evaluation — its
 * engine fields are null and `predecessorApplicationId` names the declined row, which is what the
 * four-eyes approver sees on the application.
 */
@ApplicationScoped
@Suppress("LongParameterList")
class DeclineReviewService(
    private val applications: LoanApplicationRepository,
    private val reviews: DeclineReviewRepository,
    private val events: LoanEventEmitter,
    private val clock: Clock,
    private val config: DeclineReviewConfig,
    private val workflowPort: OriginationWorkflowPort,
    private val complianceGuard: CompliancePackGuard,
    private val originationConfig: OriginationConfig,
) : DeclineReviewUseCase {

    private val machine = OriginationStateMachine()
    private val log = Logger.getLogger(DeclineReviewService::class.java)
    private val eventsOf = DeclineReviewEvents(clock)

    /**
     * Runs under the application row's lock (`withLocked`, the origination convention), so two first
     * requests for one application serialise instead of racing the unique constraint, and the
     * review row and its event commit together.
     */
    override fun requestReview(applicationId: UUID, partyId: UUID): Uni<DeclineReviewResult> =
        applications.withLocked(LoanApplicationId(applicationId)) { application ->
            if (application == null || application.partyId != partyId) {
                refused(DeclineReviewRefusedException.NOT_FOUND, "application not found")
            } else {
                reviews.loadByApplication(applicationId).flatMap { existing ->
                    // Idempotent: a repeat answers with the review that exists, whatever state it is in.
                    if (existing != null) {
                        Uni.createFrom().item(DeclineReviewResult(existing, created = false))
                    } else {
                        open(application)
                    }
                }
            }
        }

    private fun open(application: LoanApplication): Uni<DeclineReviewResult> {
        val now = OffsetDateTime.now(clock)
        val decidedAt = application.decidedEngineAt
        return when {
            !isEngineDecline(application) || decidedAt == null ->
                refused(DeclineReviewRefusedException.NOT_ENGINE_DECLINE, "only an engine decline can be reviewed")
            now.isAfter(decidedAt.plusDays(config.windowDays)) ->
                refused(DeclineReviewRefusedException.WINDOW_ELAPSED, "the review window has elapsed")
            else -> {
                val review = DeclineReview(
                    id = Ids.newId(),
                    applicationId = application.id.value,
                    partyId = application.partyId,
                    requestedAt = now,
                    engineOutcome = checkNotNull(application.decisionOutcome),
                    engineReasons = application.decisionReasons,
                    policyVersions = application.policyVersions,
                    engineDecidedAt = decidedAt,
                )
                reviews.insert(review).call { _ -> events.emit(eventsOf.requestedEvent(review)) }
                    .map { DeclineReviewResult(it, created = true) }
            }
        }
    }

    /** Declined by the engine and by nobody since: a human decision records `decidedBy`, which ends the loop. */
    private fun isEngineDecline(a: LoanApplication): Boolean =
        a.status == OriginationState.DECLINED && a.decidedBy == null && a.decisionOutcome == ENGINE_DECLINE

    override fun listPending(limit: Int): Uni<List<DeclineReview>> = reviews.listPending(limit.coerceIn(1, MAX_LIST))

    override fun decide(reviewId: UUID, request: DeclineReviewDecisionRequest, reviewer: String): Uni<DeclineReview> {
        require(reviewer.isNotBlank()) { "Reviewer identity is required" }
        return reviews.loadById(reviewId).flatMap { review ->
            when {
                review == null -> refused(DeclineReviewRefusedException.NOT_FOUND, "review not found")
                review.status != DeclineReviewStatus.REQUESTED ->
                    refused(DeclineReviewRefusedException.ALREADY_DECIDED, "review already decided")
                // A narrow guard, not a conflict-of-interest check: it fires only when the principal
                // name IS the party id. Staff principals live in a different namespace and no staff-to-
                // party mapping exists on the platform, so staff reviewing their own decline as a
                // customer passes here; the four-eyes approval of any successor is the remaining control.
                reviewer == review.partyId.toString() ->
                    refused(
                        DeclineReviewRefusedException.SELF_REVIEW,
                        "a reviewer whose principal is the applicant's party id cannot review the decline",
                    )
                // Timers are armed only after the transaction has committed the successor.
                else -> decideLocked(review, request, reviewer).call { d ->
                    d.successor?.let { armFourEyesTimer(it) } ?: Uni.createFrom().item(Unit)
                }.map { it.review }
            }
        }
    }

    /**
     * Claim the decision, write the successor (OVERTURNED only) and the events in ONE transaction,
     * under the declined application's lock: a decided review without its successor, or an announced
     * overturn that was rolled back, must not exist.
     */
    private fun decideLocked(
        review: DeclineReview,
        request: DeclineReviewDecisionRequest,
        reviewer: String,
    ): Uni<Decided> = applications.withLocked(LoanApplicationId(review.applicationId)) { original ->
        if (original == null) {
            refused(DeclineReviewRefusedException.NOT_FOUND, "declined application not found")
        } else {
            val overturn = request.outcome == DeclineReviewDecision.OVERTURNED
            val firstDue = FirstDueDate.forApplicationOn(LocalDate.now(clock))
            val notOriginable = if (overturn) {
                originabilityRefusal(original, firstDue, complianceGuard, originationConfig.floatingRateEnabled)
            } else {
                null
            }
            if (notOriginable != null) {
                // Refused before anything is written: the review stays REQUESTED.
                return@withLocked refused<Decided>(
                    DeclineReviewRefusedException.SUCCESSOR_NOT_ORIGINABLE,
                    notOriginable,
                )
            }
            val successor = if (overturn) successorOf(original, reviewer, firstDue) else null
            val decided = review.copy(
                status = request.outcome.toStatus(),
                reviewedBy = reviewer,
                reviewedAt = OffsetDateTime.now(clock),
                reviewReason = request.reason.trim(),
                successorApplicationId = successor?.id?.value,
            )
            // Successor first: the review row's successor_application_id is a foreign key, so the
            // claim can only name a row that already exists. A lost claim fails the whole
            // transaction, which takes the successor back out with it.
            (successor?.let { applications.save(it).replaceWithVoid() } ?: Uni.createFrom().voidItem())
                .flatMap {
                    reviews.claimDecision(
                        decided.id,
                        decided.status,
                        reviewer,
                        checkNotNull(decided.reviewedAt),
                        checkNotNull(decided.reviewReason),
                        decided.successorApplicationId,
                    )
                }
                .flatMap { claimed ->
                    if (claimed == 0) {
                        refused<Decided>(
                            DeclineReviewRefusedException.ALREADY_DECIDED,
                            "review already decided",
                        )
                    } else {
                        emitAll(
                            listOfNotNull(
                                eventsOf.decidedEvent(decided, reviewer),
                                successor?.let { eventsOf.successorEntryEvent(it, reviewer) },
                            ),
                        ).replaceWith(Decided(decided, successor))
                    }
                }
        }
    }

    /** The decided review and, for an overturn, the successor it committed. */
    private data class Decided(val review: DeclineReview, val successor: LoanApplication?)

    private fun emitAll(messages: List<LendingOutboxMessage>): Uni<Unit> =
        messages.fold(Uni.createFrom().item(Unit)) { acc, m -> acc.flatMap { events.emit(m) } }

    /**
     * The successor is the declined application's facts carried forward, with NO engine evidence copied
     * (the review row holds that snapshot) and the reviewer as proposer. The entry edge is checked
     * against the shared state machine (DECISION_PENDING -> FOUR_EYES is the edge the flow normally
     * takes) rather than asserted, so a change to the graph surfaces here.
     */
    private fun successorOf(original: LoanApplication, reviewer: String, firstDue: LocalDate): LoanApplication {
        // The compliance pack active TODAY, as intake pins it — not the declined row's.
        val packVersion = complianceGuard.resolveOriginationPack(original.jurisdiction, original.productType)
            ?.pack?.version
        val entry = machine.apply(
            OriginationTransition(
                applicationId = original.id.value.toString(),
                from = OriginationState.DECISION_PENDING,
                to = OriginationState.FOUR_EYES,
                actor = reviewer,
                actorKind = OriginationActorKind.HUMAN,
                reason = "decline review overturned",
                occurredAt = clock.instant(),
                packVersion = packVersion?.toString(),
            ),
        )
        check(entry is OriginationTransitionResult.Applied) { "successor entry refused by the state machine" }
        return original.copy(
            id = LoanApplicationId.random(),
            status = entry.newState,
            proposedBy = reviewer,
            decidedBy = null,
            decisionReason = null,
            decidedAt = null,
            createdAt = OffsetDateTime.now(clock),
            // The declined row's date can be in the past by now; bookLoan does not re-check it.
            firstDueDate = firstDue,
            packVersion = packVersion,
            decisionOutcome = null,
            decisionPriceBand = null,
            decisionReasons = null,
            decisionMatchedRules = null,
            policyVersions = null,
            decisionInputHash = null,
            decidedEngineAt = null,
            decisionDsti = null,
            decisionDti = null,
            signatureArtefactHash = null,
            predecessorApplicationId = original.id.value,
        )
    }

    /**
     * Same contract as `LendingService.armTimers`: read the [TimerArmingOutcome] and warn on anything
     * but ARMED, because a no-op backend answers with a value, not a failure (#6085).
     */
    private fun armFourEyesTimer(successor: LoanApplication): Uni<Unit> {
        val reflectionDays = complianceGuard.resolveOriginationPack(successor.jurisdiction, successor.productType)
            ?.pack?.reflectionPeriodDays
        return workflowPort.stateEntered(successor.id, OriginationState.FOUR_EYES, reflectionDays)
            .invoke { outcome ->
                if (outcome != TimerArmingOutcome.ARMED) {
                    log.warnf(
                        "successor application %s entered FOUR_EYES with NO durable timer armed (%s): " +
                            "its four-eyes SLA escalation is unenforced.",
                        successor.id.value,
                        outcome,
                    )
                }
            }
            .replaceWith(Unit)
            // The decision is already committed; a timer that did not arm costs the SLA escalation, not
            // the review. Logged without the cause's message, which can carry payload values.
            .onFailure().recoverWithItem { e ->
                log.errorf(
                    "four-eyes timer NOT armed for successor %s, cause=%s",
                    successor.id.value,
                    e.javaClass.simpleName,
                )
            }
    }

    private fun <T> refused(code: String, message: String): Uni<T> =
        Uni.createFrom().failure(DeclineReviewRefusedException(code, message))

    companion object {
        private const val ENGINE_DECLINE = "DECLINE"
        private const val MAX_LIST = 100
    }
}

/**
 * Intake's own refusal gates (`LendingService.applyLegacy`), re-run against today: a successor must
 * not be something intake would refuse now. Returns the refusal reason, or null if originable.
 */
private fun originabilityRefusal(
    original: LoanApplication,
    firstDue: LocalDate,
    complianceGuard: CompliancePackGuard,
    floatingRateEnabled: Boolean,
): String? = try {
    complianceGuard.checkOriginationAllowed(original.jurisdiction, original.productType)
    if (original.rateTerms.rateType != LoanRateType.FIXED && !floatingRateEnabled) {
        "FLOATING-rate origination is not enabled: the successor cannot be opened"
    } else {
        original.rateTerms.validate(firstDue)
        null
    }
} catch (e: IllegalArgumentException) {
    e.message ?: "origination is not allowed for this product"
}
