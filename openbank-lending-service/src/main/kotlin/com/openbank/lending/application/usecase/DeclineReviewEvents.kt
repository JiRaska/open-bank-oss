// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.domain.model.DeclineReview
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

    companion object {
        const val REQUESTED_EVENT_TYPE = "credit.decline_review.requested"
        private const val SCHEMA_VERSION = 1
        private val JSON = ObjectMapper()
    }
}
