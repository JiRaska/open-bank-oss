// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.application

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.referral.application.port.out.ReferralAuditRepository
import com.openbank.referral.application.port.out.ReferralProgramRepository
import com.openbank.referral.application.port.out.ReferralQualifyingFactRepository
import com.openbank.referral.application.port.out.ReferralRefereeLookup
import com.openbank.referral.domain.IneligibilityReason
import com.openbank.referral.domain.QualificationDecision
import com.openbank.referral.domain.QualificationRule
import com.openbank.referral.domain.QualifyingFact
import com.openbank.referral.domain.ReferralInvite
import com.openbank.referral.domain.ReferralReward
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** What happened to one invite when a fact was evaluated against it. */
sealed class QualificationOutcome {
    data class Qualified(val reward: ReferralReward) : QualificationOutcome()

    data class Ineligible(val inviteId: UUID, val reason: IneligibilityReason) : QualificationOutcome()
}

/**
 * ADR-0310 D1 — qualification at whichever of the two moments comes second.
 *
 * - **Event time** ([recordAccountOpened]): the fact is stored first, then every invite on which the
 *   party is already the ATTRIBUTED referee is evaluated.
 * - **Attribution time** ([attributeAndQualify]): after the invite is attributed — and on the
 *   idempotent same-referee replay too — a stored fact for the referee is evaluated.
 *
 * Both paths use [QualificationRule.decide] and then the one-reward-per-referee-per-programme check,
 * and both create the reward through [ReferralService.qualifyAttributed], whose
 * `(invite_id, qualification_event_id)` key deduplicates the two paths against each other.
 *
 * An ineligible fact is an OUTCOME: it is audited and returned, never thrown, so it cannot fail an
 * attribution. An infrastructure failure is deliberately NOT caught — at attribution time it
 * surfaces as an error so the client's idempotent retry completes the qualification; on the event
 * path it reaches the consumer, which retries and then dead-letters.
 */
@ApplicationScoped
class ReferralQualificationService(
    private val referrals: ReferralService,
    private val programs: ReferralProgramRepository,
    private val facts: ReferralQualifyingFactRepository,
    private val referees: ReferralRefereeLookup,
    private val audit: ReferralAuditRepository,
    private val clock: Clock,
) {
    suspend fun recordAccountOpened(
        partyId: UUID,
        eventId: String,
        sourceRef: String?,
        occurredAt: Instant,
        actor: String,
    ): List<QualificationOutcome> {
        val candidate = QualifyingFact(
            id = Ids.newId(),
            partyId = partyId,
            eventName = QualificationRule.ACCOUNT_OPENED,
            eventId = eventId,
            sourceRef = sourceRef,
            occurredAt = occurredAt,
            recordedAt = Instant.now(clock),
        )
        val stored = facts.recordIfAbsent(candidate).fact
        // A SECOND account for the same party conflicts on (party, event name) and returns the first
        // account's fact. That is not a new qualification, so nothing is evaluated for it. A
        // redelivery of the SAME event returns the same fact and is re-evaluated, which is
        // idempotent and is what lets a delivery that died half-way finish on the retry.
        if (stored.eventId != eventId) return emptyList()
        return referees.attributedInvites(partyId).map { evaluate(it, stored, actor) }
    }

    suspend fun attributeAndQualify(
        token: String,
        refereePartyId: UUID,
        idempotencyKey: String,
        actor: String,
    ): ReferralInvite {
        val invite = referrals.attributeInvite(token, refereePartyId, idempotencyKey, actor)
        val program = programs.find(invite.programId) ?: return invite
        val fact = facts.find(refereePartyId, program.qualifyingEvent) ?: return invite
        evaluate(invite, fact, actor)
        return invite
    }

    private suspend fun evaluate(invite: ReferralInvite, fact: QualifyingFact, actor: String): QualificationOutcome {
        val program = programs.find(invite.programId)
            ?: return reject(invite, fact, IneligibilityReason.PROGRAM_NOT_PUBLISHED, actor)
        val issuedAt = audit.issuedAt(listOf(invite.id))[invite.id]
        val decision = QualificationRule.decide(program, invite, fact, issuedAt)
        if (decision is QualificationDecision.Ineligible) return reject(invite, fact, decision.reason, actor)

        val earlier = referees.rewardForReferee(fact.partyId, program.id)
        if (earlier != null && (earlier.inviteId != invite.id || earlier.qualificationEventId != fact.eventId)) {
            return reject(invite, fact, IneligibilityReason.REFEREE_ALREADY_REWARDED, actor)
        }
        return QualificationOutcome.Qualified(referrals.qualifyAttributed(invite, program, fact.eventId, actor))
    }

    private suspend fun reject(
        invite: ReferralInvite,
        fact: QualifyingFact,
        reason: IneligibilityReason,
        actor: String,
    ): QualificationOutcome {
        audit.append(
            "QUALIFICATION_REJECTED",
            invite.id,
            actor,
            "reason=${reason.name} event=${fact.eventName} eventId=${fact.eventId}",
            Instant.now(clock),
        )
        return QualificationOutcome.Ineligible(invite.id, reason)
    }
}
