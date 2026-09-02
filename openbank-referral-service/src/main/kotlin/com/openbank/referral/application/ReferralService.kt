// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.application

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.referral.application.port.out.ReferralAuditRepository
import com.openbank.referral.application.port.out.ReferralEventPublisher
import com.openbank.referral.application.port.out.ReferralInviteRepository
import com.openbank.referral.application.port.out.ReferralProgramRepository
import com.openbank.referral.application.port.out.ReferralRewardRepository
import com.openbank.referral.domain.InviteStatus
import com.openbank.referral.domain.LedgerOutcome
import com.openbank.referral.domain.ProgramStatus
import com.openbank.referral.domain.ReferralConflictException
import com.openbank.referral.domain.ReferralEvent
import com.openbank.referral.domain.ReferralInvite
import com.openbank.referral.domain.ReferralNotFoundException
import com.openbank.referral.domain.ReferralProgram
import com.openbank.referral.domain.ReferralPublishOutcome
import com.openbank.referral.domain.ReferralReward
import com.openbank.referral.domain.ReferralValidationException
import com.openbank.referral.domain.RewardStatus
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val TOKEN_BYTES = 32
private const val DEFAULT_WINDOW_DAYS = 30L

@ApplicationScoped
class ReferralService(
    private val programs: ReferralProgramRepository,
    private val invites: ReferralInviteRepository,
    private val rewards: ReferralRewardRepository,
    private val events: ReferralEventPublisher,
    private val audit: ReferralAuditRepository,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    suspend fun createProgram(
        name: String,
        version: Int,
        rewardAmount: BigDecimal,
        currency: String,
        qualifyingEvent: String,
        attributionWindowEndsAt: Instant?,
        maker: String,
    ): ReferralProgram {
        validate(name.matches(Regex("[a-z0-9-]{3,64}")), "name must be a stable slug")
        validate(version > 0, "version must be positive")
        validate(rewardAmount > BigDecimal.ZERO, "rewardAmount must be positive")
        validate(currency.matches(Regex("[A-Z]{3}")), "currency must be an ISO-4217 code")
        validate(qualifyingEvent.matches(Regex("[a-z0-9_.-]{3,128}")), "qualifyingEvent must be a stable event key")
        val now = Instant.now(clock)
        val program = ReferralProgram(
            id = Ids.newId(),
            name = name,
            version = version,
            rewardAmount = rewardAmount,
            currency = currency,
            qualifyingEvent = qualifyingEvent,
            attributionWindowEndsAt = attributionWindowEndsAt ?: now.plus(Duration.ofDays(DEFAULT_WINDOW_DAYS)),
            status = ProgramStatus.DRAFT,
            maker = maker,
            checker = null,
            createdAt = now,
            publishedAt = null,
        )
        val created = programs.create(program)
        audit.append("PROGRAM_DRAFTED", created.id, maker, "${created.name}@${created.version}", now)
        return created
    }

    // ThrowsCount: three distinct guard-clause rejections, each a different machine-readable
    // reason the caller can branch on — collapsing them into one exception would erase that.
    @Suppress("ThrowsCount")
    suspend fun publishProgram(id: UUID, checker: String): ReferralProgram {
        val existing = programs.find(id) ?: throw ReferralNotFoundException("program $id not found")
        if (existing.maker == checker) throw ReferralConflictException("maker cannot publish their own program")
        if (existing.status != ProgramStatus.DRAFT) throw ReferralConflictException("program is not a draft")
        val now = Instant.now(clock)
        val published = programs.publish(id, existing.maker, checker, now)
            ?: throw ReferralConflictException("program could not be published")
        audit.append("PROGRAM_PUBLISHED", id, checker, "${published.name}@${published.version}", now)
        return published
    }

    // ThrowsCount: three distinct guard-clause rejections, each a different machine-readable
    // reason the caller can branch on — collapsing them into one exception would erase that.
    @Suppress("ThrowsCount")
    suspend fun issueInvite(
        programId: UUID,
        referrerPartyId: UUID,
        idempotencyKey: String,
        actor: String,
    ): ReferralInvite {
        validate(idempotencyKey.isNotBlank(), "Idempotency-Key is required")
        if (invites.findByIdempotencyKey(idempotencyKey) != null) {
            throw ReferralConflictException("Idempotency-Key has already been used")
        }
        val program = programs.find(programId) ?: throw ReferralNotFoundException("program $programId not found")
        val now = Instant.now(clock)
        if (program.status != ProgramStatus.PUBLISHED || !program.attributionWindowEndsAt.isAfter(now)) {
            throw ReferralConflictException("program is not published or has expired")
        }
        val token = randomToken()
        val invite = ReferralInvite(
            id = Ids.newId(),
            programId = program.id,
            token = token,
            referrerPartyId = referrerPartyId,
            refereePartyId = null,
            status = InviteStatus.ISSUED,
            expiresAt = program.attributionWindowEndsAt,
            idempotencyKey = idempotencyKey,
            attributedAt = null,
        )
        val created = invites.create(invite)
        audit.append("INVITE_ISSUED", created.id, actor, "token_hash=${hash(token)}", now)
        return created
    }

    // ThrowsCount: four distinct guard-clause rejections, each a different machine-readable
    // reason the caller can branch on — collapsing them into one exception would erase that.
    @Suppress("ThrowsCount")
    suspend fun attributeInvite(
        token: String,
        refereePartyId: UUID,
        idempotencyKey: String,
        actor: String,
    ): ReferralInvite {
        validate(token.isNotBlank(), "invite token is required")
        validate(idempotencyKey.isNotBlank(), "Idempotency-Key is required")
        val invite = invites.findByToken(hash(token)) ?: throw ReferralNotFoundException("invite not found")
        val now = Instant.now(clock)
        if (!invite.expiresAt.isAfter(now)) throw ReferralConflictException("invite has expired")
        if (invite.referrerPartyId == refereePartyId) throw ReferralConflictException("self-referral is not allowed")
        if (invite.status == InviteStatus.ATTRIBUTED) {
            if (invite.refereePartyId == refereePartyId) return invite
            throw ReferralConflictException("invite is already attributed")
        }
        if (invite.status != InviteStatus.ISSUED) throw ReferralConflictException("invite is not attributable")
        val attributed = invites.attribute(invite.id, refereePartyId, now)
        audit.append("INVITE_ATTRIBUTED", attributed.id, actor, "referee=$refereePartyId", now)
        return attributed
    }

    // ThrowsCount: distinct guard-clause rejections, each a different machine-readable reason
    // the caller can branch on — collapsing them into one exception would erase that.
    @Suppress("ThrowsCount")
    suspend fun qualifyInvite(
        token: String,
        eventName: String,
        eventId: String,
        idempotencyKey: String,
        actor: String,
    ): ReferralReward {
        validate(eventName.isNotBlank(), "eventName is required")
        validate(eventId.isNotBlank(), "eventId is required")
        validate(idempotencyKey.isNotBlank(), "Idempotency-Key is required")
        val invite = invites.findByToken(hash(token)) ?: throw ReferralNotFoundException("invite not found")
        val program = programs.find(invite.programId) ?: throw ReferralNotFoundException("program not found")
        if (program.qualifyingEvent != eventName) throw ReferralConflictException("event does not qualify this program")
        if (invite.status != InviteStatus.ATTRIBUTED || invite.refereePartyId == null) {
            throw ReferralConflictException("invite must be attributed before qualification")
        }
        rewards.findByInviteAndEvent(invite.id, eventId)?.let { return it }
        val now = Instant.now(clock)
        val reward = ReferralReward(
            id = Ids.newId(),
            inviteId = invite.id,
            programId = program.id,
            referrerPartyId = invite.referrerPartyId,
            refereePartyId = invite.refereePartyId,
            qualificationEventId = eventId,
            rewardReference = "referral-${invite.id}-$eventId",
            amount = program.rewardAmount,
            currency = program.currency,
            status = RewardStatus.REWARD_REQUESTED,
            createdAt = now,
            requestedAt = now,
            rewardedAt = null,
        )
        val created = rewards.create(reward)
        publishAudited(
            ReferralEvent.Qualified(
                eventId = Ids.randomId(),
                occurredAt = now,
                programId = program.id,
                inviteId = invite.id,
                referrerPartyId = invite.referrerPartyId,
                refereePartyId = invite.refereePartyId,
                qualificationEventId = eventId,
            ),
            created.id,
            actor,
            now,
        )
        publishAudited(
            ReferralEvent.RewardRequested(
                eventId = Ids.randomId(),
                occurredAt = now,
                programId = program.id,
                inviteId = invite.id,
                rewardReference = created.rewardReference,
                amount = created.amount,
                currency = created.currency,
            ),
            created.id,
            actor,
            now,
        )
        audit.append("REWARD_REQUESTED", created.id, actor, created.rewardReference, now)
        return created
    }

    /** Contract boundary only: a future ledger adapter is the sole producer of these outcomes. */
    suspend fun applyLedgerOutcome(reference: String, outcome: LedgerOutcome, actor: String): ReferralReward {
        val reward = rewards.findByReference(reference) ?: throw ReferralNotFoundException("reward not found")
        val now = Instant.now(clock)
        val next = when (outcome) {
            LedgerOutcome.ACCEPTED -> RewardStatus.REWARDED
            LedgerOutcome.REJECTED -> RewardStatus.RETRYABLE
            LedgerOutcome.REVERSED -> RewardStatus.REVERSED
        }
        val updated = rewards.outcome(reference, next.name, now)
        publishAudited(
            ReferralEvent.RewardOutcome(
                eventId = Ids.randomId(),
                occurredAt = now,
                programId = reward.programId,
                inviteId = reward.inviteId,
                rewardReference = reference,
                outcome = outcome,
            ),
            updated.id,
            actor,
            now,
        )
        audit.append("LEDGER_${outcome.name}", updated.id, actor, reference, now)
        return updated
    }

    /**
     * Publishes [event] and records the transport outcome in the audit trail when nothing left the
     * process. An undelivered money-path event must be visible in the evidentiary record, not only
     * in a log line — the caller cannot otherwise tell a dropped reward from a delivered one.
     */
    private suspend fun publishAudited(event: ReferralEvent, aggregateId: UUID, actor: String, at: Instant) {
        val outcome = events.publish(event)
        if (outcome != ReferralPublishOutcome.HANDED_TO_TRANSPORT) {
            audit.append(
                "EVENT_NOT_PUBLISHED",
                aggregateId,
                actor,
                "type=${event.eventType} eventId=${event.eventId} outcome=${outcome.name}",
                at,
            )
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun validate(condition: Boolean, message: String) {
        if (!condition) throw ReferralValidationException(message)
    }

    companion object {
        fun hash(token: String): String = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
