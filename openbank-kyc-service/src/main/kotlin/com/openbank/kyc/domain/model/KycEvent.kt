// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A KYC case lifecycle event, together with the exact flat JSON envelope that goes on the wire
 * (topic `openbank.kyc.events`).
 *
 * [envelope] is deliberately the whole message body rather than a set of typed fields: the
 * envelope IS the contract downstream services parse, and it is pinned by the provider-side
 * pacts (`KycEventPactProviderVerificationTest`). Serialization happens in the infrastructure
 * layer — this stays framework-free.
 */
data class KycEvent(
    val eventType: String,
    val aggregateId: UUID,
    val occurredAt: Instant,
    val envelope: Map<String, Any?>,
)

/**
 * Builds the KYC case lifecycle events.
 *
 * These used to be built inside `KycEventPublisher`, a bare `@Channel("kyc-events-out")` emitter
 * that fired AFTER the repository transaction had already committed — a dual write. The events
 * now travel through `kyc_outbox`, written in the same transaction as the state change (issue
 * #4007), so the envelope construction had to move somewhere both the use case and the repository
 * can see. Field names, field order and the flat (non-nested) shape are unchanged, and the outbox
 * channel publishes to the same topic, so no consumer sees a difference.
 */
object KycEvents {

    fun caseOpened(case: KycCase, at: Instant): KycEvent = lifecycle("KYC_CASE_OPENED", case, at)

    fun caseStatusChanged(case: KycCase, at: Instant): KycEvent = lifecycle("KYC_CASE_STATUS_CHANGED", case, at)

    fun caseApproved(case: KycCase, at: Instant): KycEvent = lifecycle("KYC_CASE_APPROVED", case, at)

    fun caseRejected(case: KycCase, at: Instant): KycEvent = lifecycle("KYC_CASE_REJECTED", case, at)

    private fun lifecycle(eventType: String, case: KycCase, at: Instant): KycEvent = KycEvent(
        eventType = eventType,
        aggregateId = case.id,
        occurredAt = at,
        envelope = linkedMapOf(
            "eventType" to eventType,
            "kycCaseId" to case.id,
            "partyId" to case.partyId,
            "status" to case.status,
            "riskLevel" to case.riskLevel,
            "occurredAt" to at,
        ),
    )
}
