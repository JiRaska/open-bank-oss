// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A pKYC re-screening trigger (ADR-0256): a durable record that a screening case must be opened
 * for a party through the case coordinator. Triggers only ever OPEN a case — disposition stays
 * with a human (ADR-0256 D2), so there is no terminal "concluded" state here; [OPENED] means the
 * coordinator accepted the case (or already had one), [OPEN_FAILED] means the open is outstanding
 * and owned by the retry job.
 */
enum class ScreeningTriggerState { PENDING, OPENED, OPEN_FAILED }

/** Why an open failed, carried on the row and on the KYC_COORDINATOR_OPEN_FAILED event. */
enum class ScreeningTriggerFailure {
    /** 429/503/timeout from the coordinator, or resilience wrappers exhausted. */
    COORDINATOR_UNAVAILABLE,

    /** A 4xx the retry cannot fix (bad request, asserted-identity 403). */
    COORDINATOR_REJECTED,

    /** `openbank.kyc.case-coordinator.url` is absent while the adapter is enabled. */
    NOT_CONFIGURED,
}

/**
 * The diff that fired the trigger, retained per party so "why is this customer under review"
 * has a one-row answer and the publication is replayable without the Kafka retention window.
 */
data class ScreeningTriggerDiff(
    val changedExternalIds: List<String>,
    val deactivatedExternalIds: List<String>,
    val changeCount: Int,
)

data class ScreeningTrigger(
    val triggerId: UUID,
    val partyId: UUID,
    val publicationId: UUID,
    val chunkIndex: Long,
    val listType: String?,
    val diff: ScreeningTriggerDiff,
    val state: ScreeningTriggerState,
    val coordinatorCaseId: String?,
    val attempts: Int,
    val failedReason: ScreeningTriggerFailure?,
    val nextAttemptAt: Instant?,
    val createdAt: Instant,
    val resolvedAt: Instant?,
) {
    companion object {
        const val TRIGGER_TYPE_SANCTIONS_LIST_CHANGED = "SANCTIONS_LIST_CHANGED"

        fun pending(
            partyId: UUID,
            publicationId: UUID,
            chunkIndex: Long,
            listType: String?,
            diff: ScreeningTriggerDiff,
            now: Instant,
        ) = ScreeningTrigger(
            triggerId = UUID.randomUUID(),
            partyId = partyId,
            publicationId = publicationId,
            chunkIndex = chunkIndex,
            listType = listType,
            diff = diff,
            state = ScreeningTriggerState.PENDING,
            coordinatorCaseId = null,
            attempts = 0,
            failedReason = null,
            nextAttemptAt = null,
            createdAt = now,
            resolvedAt = null,
        )
    }
}

/**
 * Builds the outbox envelope for the durable failure path. Written in the SAME transaction as
 * the trigger's state flip (contract: docs/design/adr-0256-kyc-case-coordinator-contract.md —
 * "written transactionally with the trigger state"), so the trigger can never read OPEN_FAILED
 * without the operator-visible event existing.
 */
object ScreeningTriggerEvents {

    const val EVENT_COORDINATOR_OPEN_FAILED = "KYC_COORDINATOR_OPEN_FAILED"

    fun coordinatorOpenFailed(trigger: ScreeningTrigger, at: Instant): KycEvent = KycEvent(
        eventType = EVENT_COORDINATOR_OPEN_FAILED,
        aggregateId = trigger.triggerId,
        occurredAt = at,
        envelope = linkedMapOf(
            "eventType" to EVENT_COORDINATOR_OPEN_FAILED,
            "triggerId" to trigger.triggerId,
            "partyId" to trigger.partyId,
            "triggerType" to ScreeningTrigger.TRIGGER_TYPE_SANCTIONS_LIST_CHANGED,
            "publicationId" to trigger.publicationId,
            "state" to trigger.state.name,
            "failedReason" to trigger.failedReason?.name,
            "attempts" to trigger.attempts,
            "nextAttemptAt" to trigger.nextAttemptAt,
            "occurredAt" to at,
            "sourceService" to "kyc-service",
        ),
    )
}
