// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.domain

import java.time.Instant
import java.util.UUID

/**
 * ADR-0310 D1 — something that happened to a party which a programme may qualify on, recorded
 * BEFORE anyone knows whether it will matter. The referee opens the account during onboarding,
 * before they can sign in and redeem an invite, so a fact that is not stored when it arrives is
 * lost by the time the attribution that would use it happens.
 *
 * [eventId] is the producer's envelope id and the qualification idempotency key; [sourceRef] is
 * evidence (the account id) and never an input to a decision.
 */
data class QualifyingFact(
    val id: UUID,
    val partyId: UUID,
    val eventName: String,
    val eventId: String,
    val sourceRef: String?,
    val occurredAt: Instant,
    val recordedAt: Instant,
)

/** Why a fact did not qualify an invite. Recorded in the audit trail, never swallowed. */
enum class IneligibilityReason {
    PROGRAM_NOT_PUBLISHED,
    EVENT_DOES_NOT_QUALIFY,
    INVITE_NOT_ATTRIBUTED,
    FACT_IS_FOR_ANOTHER_PARTY,
    INVITE_ISSUE_TIME_UNKNOWN,
    FACT_BEFORE_INVITE_ISSUED,
    FACT_AFTER_INVITE_EXPIRED,
    REFEREE_ALREADY_REWARDED,
}

/** A sealed decision, not a Boolean: an ineligible fact must carry its reason into the audit row. */
sealed class QualificationDecision {
    object Eligible : QualificationDecision()

    data class Ineligible(val reason: IneligibilityReason) : QualificationDecision()
}

/**
 * The one eligibility rule, applied identically at event time and at attribution time. Keeping it
 * a pure function is what makes "the two paths agree" a unit-testable property rather than a hope.
 */
object QualificationRule {
    /** The programme key published programmes use. Programme revisions are immutable (ADR-0266). */
    const val ACCOUNT_OPENED: String = "account.opened"

    /**
     * The closed translation from a producer's wire event type to a programme key. Neither side is
     * renamed: `AccountCreated` is a discriminator four other consumers read verbatim, and a
     * published programme's `qualifyingEvent` cannot change. An event type absent here is ignored.
     */
    private val WIRE_EVENT_TO_KEY: Map<String, String> = mapOf("AccountCreated" to ACCOUNT_OPENED)

    fun qualifyingKeyFor(wireEventType: String): String? = WIRE_EVENT_TO_KEY[wireEventType]

    /**
     * [inviteIssuedAt] comes from the invite's `INVITE_ISSUED` audit row — the only place the issue
     * instant is recorded. When it is missing the invite does NOT qualify: the lower bound is the
     * anti-farming control (an existing customer redeeming a code against an account opened long
     * before the invite existed), and an unknown bound must fail closed.
     */
    // ReturnCount: each guard is a distinct, auditable reason; a single expression would hide which.
    @Suppress("ReturnCount")
    fun decide(
        program: ReferralProgram,
        invite: ReferralInvite,
        fact: QualifyingFact,
        inviteIssuedAt: Instant?,
    ): QualificationDecision {
        if (program.status != ProgramStatus.PUBLISHED) return ineligible(IneligibilityReason.PROGRAM_NOT_PUBLISHED)
        if (program.qualifyingEvent != fact.eventName) return ineligible(IneligibilityReason.EVENT_DOES_NOT_QUALIFY)
        if (invite.status != InviteStatus.ATTRIBUTED) return ineligible(IneligibilityReason.INVITE_NOT_ATTRIBUTED)
        if (invite.refereePartyId != fact.partyId) return ineligible(IneligibilityReason.FACT_IS_FOR_ANOTHER_PARTY)
        if (inviteIssuedAt == null) return ineligible(IneligibilityReason.INVITE_ISSUE_TIME_UNKNOWN)
        val opened = fact.occurredAt
        if (opened.isBefore(inviteIssuedAt)) return ineligible(IneligibilityReason.FACT_BEFORE_INVITE_ISSUED)
        if (!opened.isBefore(invite.expiresAt)) return ineligible(IneligibilityReason.FACT_AFTER_INVITE_EXPIRED)
        return QualificationDecision.Eligible
    }

    private fun ineligible(reason: IneligibilityReason) = QualificationDecision.Ineligible(reason)
}
