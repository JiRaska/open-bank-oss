// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class ProgramStatus { DRAFT, PUBLISHED, EXPIRED }

enum class InviteStatus { ISSUED, ATTRIBUTED, EXPIRED, REJECTED }

enum class RewardStatus { QUALIFIED, REWARD_REQUESTED, REWARDED, RETRYABLE, REVERSED }

enum class LedgerOutcome { ACCEPTED, REJECTED, REVERSED }

data class ReferralProgram(
    val id: UUID,
    val name: String,
    val version: Int,
    val rewardAmount: BigDecimal,
    val currency: String,
    val qualifyingEvent: String,
    val attributionWindowEndsAt: Instant,
    val status: ProgramStatus,
    val maker: String,
    val checker: String?,
    val createdAt: Instant,
    val publishedAt: Instant?,
)

data class ReferralInvite(
    val id: UUID,
    val programId: UUID,
    val token: String,
    val referrerPartyId: UUID,
    val refereePartyId: UUID?,
    val status: InviteStatus,
    val expiresAt: Instant,
    val idempotencyKey: String,
    val attributedAt: Instant?,
)

data class ReferralReward(
    val id: UUID,
    val inviteId: UUID,
    val programId: UUID,
    val referrerPartyId: UUID,
    val refereePartyId: UUID,
    val qualificationEventId: String,
    val rewardReference: String,
    val amount: BigDecimal,
    val currency: String,
    val status: RewardStatus,
    val createdAt: Instant,
    val requestedAt: Instant?,
    val rewardedAt: Instant?,
)

sealed class ReferralEvent {
    abstract val eventType: String
    abstract val eventId: UUID
    abstract val occurredAt: Instant
    abstract val programId: UUID
    abstract val inviteId: UUID

    /**
     * Producing service, read by `AuditConsumer.resolveSourceService` (audit-service) as the
     * strongest (EVENT-sourced) attribution — issues #5256/#6035. Serialised via
     * `objectMapper.writeValueAsString` in `ReferralService`, so the wire key exists only as
     * this Kotlin property name (mirrors `FxEvent.sourceService`).
     */
    val sourceService: String = SOURCE_SERVICE

    companion object {
        internal const val SOURCE_SERVICE = "referral-service"
    }

    data class Qualified(
        override val eventId: UUID,
        override val occurredAt: Instant,
        override val programId: UUID,
        override val inviteId: UUID,
        val referrerPartyId: UUID,
        val refereePartyId: UUID,
        val qualificationEventId: String,
    ) : ReferralEvent() {
        override val eventType = "Qualified"
    }

    data class RewardRequested(
        override val eventId: UUID,
        override val occurredAt: Instant,
        override val programId: UUID,
        override val inviteId: UUID,
        val rewardReference: String,
        val amount: BigDecimal,
        val currency: String,
    ) : ReferralEvent() {
        override val eventType = "RewardRequested"
    }

    data class RewardOutcome(
        override val eventId: UUID,
        override val occurredAt: Instant,
        override val programId: UUID,
        override val inviteId: UUID,
        val rewardReference: String,
        val outcome: LedgerOutcome,
    ) : ReferralEvent() {
        override val eventType = "RewardOutcome"
    }
}

/**
 * A referrer's own view of one invite. Carries NOTHING about the referee — no party id, no name —
 * and not the token (stored only as a hash, and a bearer secret once issued). [status] is already
 * time-adjusted: an ISSUED invite whose window has passed reads as EXPIRED.
 *
 * [createdAt] comes from the invite's `INVITE_ISSUED` audit row, the only place the issue instant
 * is recorded; it is null if that row is missing rather than guessed from another timestamp.
 */
data class ReferrerInviteView(
    val id: UUID,
    val status: InviteStatus,
    val createdAt: Instant?,
    val expiresAt: Instant,
    val attributedAt: Instant?,
    val reward: ReferrerRewardView?,
)

/** The referrer-side projection of the latest reward on an invite. */
data class ReferrerRewardView(
    val status: RewardStatus,
    val amount: BigDecimal,
    val currency: String,
    val requestedAt: Instant?,
    val rewardedAt: Instant?,
)

/**
 * Machine-readable causes for a 409, so a caller (the customer edge) can branch without matching
 * on message text. Null for conflicts nobody has needed to distinguish yet.
 */
enum class ReferralConflictReason {
    EXPIRED,
    SELF,
    ALREADY_ATTRIBUTED,
    NOT_ATTRIBUTABLE,
    IDEMPOTENCY_KEY_REUSED,
    PROGRAM_UNAVAILABLE,
}

class ReferralConflictException(message: String, val reason: ReferralConflictReason? = null) :
    RuntimeException(message)

class ReferralNotFoundException(message: String) : RuntimeException(message)

class ReferralValidationException(message: String) : RuntimeException(message)
