// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.domain.model.DeclineReview
import com.openbank.lending.domain.model.DeclineReviewStatus
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.libs.lending.origination.OriginationActorKind
import java.time.Clock

/**
 * The decline-review events: ids and machine codes only, versioned (`schemaVersion`), in the same
 * envelope as the origination transition events. Free text (the reviewer's reason) is never put on an
 * event; it stays in the review row.
 */
internal class DeclineReviewEvents(private val clock: Clock) {

    fun requestedEvent(r: DeclineReview) = event(
        REQUESTED_EVENT_TYPE,
        r,
        actor = r.partyId.toString(),
        actorKind = OriginationActorKind.HUMAN,
        extra = linkedMapOf(
            "engineOutcome" to r.engineOutcome,
            // "CODE:ruleId" pairs — the engine's own machine-readable reasons, no free text.
            "engineReasonCodes" to r.engineReasons?.split(',')?.filter { it.isNotBlank() },
            "policyVersions" to r.policyVersions,
        ),
    )

    fun decidedEvent(r: DeclineReview, reviewer: String) = event(
        if (r.status == DeclineReviewStatus.OVERTURNED) OVERTURNED_EVENT_TYPE else CONFIRMED_EVENT_TYPE,
        r,
        actor = reviewer,
        actorKind = OriginationActorKind.HUMAN,
        extra = linkedMapOf("successorApplicationId" to r.successorApplicationId?.toString()),
    )

    private fun event(
        type: String,
        r: DeclineReview,
        actor: String,
        actorKind: OriginationActorKind,
        extra: Map<String, Any?>,
    ): LendingOutboxMessage {
        val id = r.applicationId
        val payload = JSON.writeValueAsString(
            linkedMapOf<String, Any?>(
                "eventType" to type,
                "schemaVersion" to SCHEMA_VERSION,
                "aggregateType" to "LOAN_APPLICATION",
                "aggregateId" to id.toString(),
                "loanApplicationId" to id.toString(),
                "reviewId" to r.id.toString(),
                "partyId" to r.partyId.toString(),
                "reviewStatus" to r.status.name,
                "actorId" to actor,
                "actorKind" to actorKind.name,
                "occurredAt" to clock.instant().toString(),
                "correlationId" to id.toString(),
                "sourceService" to "lending",
            ).also { it.putAll(extra) },
        )
        return LendingOutboxMessage(aggregateId = id, eventType = type, payload = payload)
    }

    /** The successor's first lifecycle record, in the `credit.application.transition` envelope, for its own evidence chain. */
    fun successorEntryEvent(successor: LoanApplication, reviewer: String): LendingOutboxMessage {
        val id = successor.id.value
        val payload = JSON.writeValueAsString(
            linkedMapOf<String, Any?>(
                "eventType" to TRANSITION_EVENT_TYPE,
                "aggregateType" to "LOAN_APPLICATION",
                "aggregateId" to id.toString(),
                "loanApplicationId" to id.toString(),
                "partyId" to successor.partyId.toString(),
                "fromState" to "NONE",
                "toState" to successor.status.name,
                "actorId" to reviewer,
                "actorKind" to OriginationActorKind.HUMAN.name,
                "reason" to "opened by decline review overturn",
                "predecessorApplicationId" to successor.predecessorApplicationId.toString(),
                "packVersion" to successor.packVersion,
                "occurredAt" to clock.instant().toString(),
                "correlationId" to id.toString(),
                "sourceService" to "lending",
            ),
        )
        return LendingOutboxMessage(aggregateId = id, eventType = TRANSITION_EVENT_TYPE, payload = payload)
    }

    companion object {
        const val REQUESTED_EVENT_TYPE = "credit.decline_review.requested"
        const val CONFIRMED_EVENT_TYPE = "credit.decline_review.confirmed"
        const val OVERTURNED_EVENT_TYPE = "credit.decline_review.overturned"
        private const val TRANSITION_EVENT_TYPE = "credit.application.transition"
        private const val SCHEMA_VERSION = 1
        private val JSON = ObjectMapper()
    }
}
