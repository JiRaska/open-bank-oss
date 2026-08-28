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
    /**
     * A consumer must only project the versioned evidence it understands.  The referral topic is
     * shared and retained, so inferring a schema from a Kotlin class name would make a later
     * producer change silently rewrite reporting history.
     */
    abstract val schemaVersion: Int
    abstract val eventType: String
    abstract val eventId: UUID
    abstract val occurredAt: Instant
    abstract val programId: UUID
    abstract val programVersion: Int
    abstract val inviteId: UUID

    data class Qualified(
        override val schemaVersion: Int = 2,
        override val eventId: UUID,
        override val occurredAt: Instant,
        override val programId: UUID,
        override val programVersion: Int,
        override val inviteId: UUID,
        val qualificationEventId: String,
    ) : ReferralEvent() {
        override val eventType = "QualifiedV2"
    }

    data class RewardRequested(
        override val schemaVersion: Int = 2,
        override val eventId: UUID,
        override val occurredAt: Instant,
        override val programId: UUID,
        override val programVersion: Int,
        override val inviteId: UUID,
        val rewardReference: String,
        val amount: BigDecimal,
        val currency: String,
    ) : ReferralEvent() {
        override val eventType = "RewardRequestedV2"
    }

    data class RewardOutcome(
        override val schemaVersion: Int = 2,
        override val eventId: UUID,
        override val occurredAt: Instant,
        override val programId: UUID,
        override val programVersion: Int,
        override val inviteId: UUID,
        val rewardReference: String,
        val outcome: LedgerOutcome,
    ) : ReferralEvent() {
        override val eventType = "RewardOutcomeV2"
    }
}

class ReferralConflictException(message: String) : RuntimeException(message)

class ReferralNotFoundException(message: String) : RuntimeException(message)

class ReferralValidationException(message: String) : RuntimeException(message)
