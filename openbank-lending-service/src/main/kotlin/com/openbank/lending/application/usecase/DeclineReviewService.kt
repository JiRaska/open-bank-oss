// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.DeclineReviewRepository
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.domain.model.DeclineReview
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.lending.origination.OriginationState
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Customer-requested human review of a decision-engine decline.
 *
 * The declined application stays DECLINED, which is terminal in the shared origination graph
 * (ADR-0211 D1) — nothing here touches that graph. The review is its own record, one per application.
 * This slice records the request, with the engine's reasons and policy versions snapshotted for the
 * reviewer; the staff decision follows separately.
 *
 * Events carry identifiers and machine codes only.
 */
@ApplicationScoped
class DeclineReviewService(
    private val applications: LoanApplicationRepository,
    private val reviews: DeclineReviewRepository,
    private val events: LoanEventEmitter,
    private val clock: Clock,
    private val config: DeclineReviewConfig,
) : DeclineReviewUseCase {

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

    private fun <T> refused(code: String, message: String): Uni<T> =
        Uni.createFrom().failure(DeclineReviewRefusedException(code, message))

    private companion object {
        const val ENGINE_DECLINE = "DECLINE"
    }
}
