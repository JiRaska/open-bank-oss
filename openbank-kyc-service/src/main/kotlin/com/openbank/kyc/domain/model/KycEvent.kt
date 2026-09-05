// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.domain.model

import com.openbank.libs.domain.event.EventActor
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
            EventActor.FIELD_ACTOR_ID to actorId(case),
            EventActor.FIELD_ACTOR_TYPE to actorType(case),
            // Producing service, read by `AuditConsumer.resolveSourceService` as the strongest
            // (EVENT-sourced) attribution — issue #3994/#5256. `eventType` here is already
            // SCREAMING_SNAKE_CASE (KYC_CASE_OPENED etc.) and load-bearing for
            // onboarding-service's `OnboardingEventConsumer` and party-service's
            // `KycAmlEventConsumer`, so it is unchanged. `sourceService` has no such consumer,
            // so it is safe to add net-new. Value matches the fleet's audit convention: the
            // module directory without the `openbank-` prefix, the same spelling
            // `TopicAttribution` already maps `openbank.kyc.events` to and this file's own
            // `actorId`/`SOURCE_SERVICE` constant already uses for the SYSTEM actor id.
            "sourceService" to SOURCE_SERVICE,
        ),
    )

    /**
     * Who caused this transition (#3994).
     *
     * This service is the one place in the survey where the aggregate ALREADY knows the human:
     * `approveCase`/`rejectCase` take the reviewer from the authenticated security context — never
     * from the request body, per the ČNB AML/KYC §8 four-eyes mandate (ADR-0068) — and store it on
     * [KycCase.reviewedBy]. It was simply never put on the wire, so 117 audit rows
     * (`KYC_CASE_OPENED` + `KYC_CASE_APPROVED`) recorded a four-eyes decision with no eyes.
     *
     * A case with no reviewer has genuinely had no human touch it: `KYC_CASE_OPENED` is opened by
     * the `PARTY_CREATED` consumer, and the check-result transitions are screening callbacks. Those
     * get the `SYSTEM` id rather than a borrowed identity.
     *
     * Note the sandbox path: `autoEvaluateAndApprove` writes the literal `"sandbox-auto-approval"`
     * into `reviewedBy`, so that string flows through here as-is. That is deliberate — it is what
     * actually approved the case, it is already the stored value, and rewriting it to a `SYSTEM` id
     * here would make the event disagree with the row it describes. It is also self-evidently not a
     * person, which is the property that matters.
     */
    private fun actorId(case: KycCase): String = case.reviewedBy?.takeIf { it.isNotBlank() }
        ?: EventActor.system(SOURCE_SERVICE, "case-lifecycle")

    private fun actorType(case: KycCase): String =
        if (case.reviewedBy.isNullOrBlank()) EventActor.TYPE_SYSTEM else ACTOR_TYPE_REVIEWER

    /**
     * This module's own `sourceService`, and the SYSTEM actor id. Named `SOURCE_SERVICE` rather
     * than `SERVICE` because `OrphanedPartyGauge` declares a metrics-tag `const val SERVICE =
     * "kyc"` in the same module: two different values behind one simple name make every
     * reference ambiguous to `check-source-service-convention.py`, which then reports UNRESOLVED
     * instead of guessing. The VALUE is unchanged — the module directory without the prefix.
     */
    private const val SOURCE_SERVICE = "kyc-service"

    /**
     * A named human reviewer. Not `OPERATOR`: the audit trail's existing `actorType` vocabulary uses
     * `CUSTOMER` for a self-service subject, and a KYC reviewer is neither the subject nor a generic
     * operator — the four-eyes record is specifically about the reviewing analyst.
     */
    private const val ACTOR_TYPE_REVIEWER = "REVIEWER"
}
