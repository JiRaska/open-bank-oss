// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A party's engagement profile in the rewards hub (ADR-0220 D3). Contains their streak, earned
 * points and badge set, plus the opt-in flag that gates all personalised engagement for the party.
 *
 * **Safety invariants — encoded as domain rules, tested as domain tests, not content policy:**
 *
 * 1. No reward may be linked to credit uptake, credit utilisation or any risk-increasing behaviour
 *    — [EarnSource] enumerates the only legal sources.
 * 2. The rewards hub is opt-in (`enrolled = false` by default); leaving is one tap and keeps
 *    earned value — earned points and badges are never forfeited on opt-out.
 * 3. No fake urgency: countdowns exist only on genuinely expiring items; [Challenge] holds a
 *    nullable `expiresAt` set only when a real deadline exists.
 * 4. Reward economics are capped per party per year (enforced via [yearlyRewardCap] and
 *    [earnedThisYear]) and provisioned through the billing path, never marketing cash.
 * 5. Vulnerable customers (adverse-state flag) are excluded from [Challenge] targeting — the
 *    flag is set by the event consumer from the fleet-wide adverse-state events (fraud hold,
 *    arrears, collections contact, open dispute) and clears on resolution.
 */
data class EngagementProfile(
    val partyId: UUID,
    val enrolled: Boolean,
    val adverseState: Boolean,
    val streakDays: Int,
    val lastActivityAt: Instant?,
    val totalPoints: Int,
    val earnedThisYear: Int,
    val badges: Set<BadgeType>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(streakDays >= 0) { "streak must not be negative" }
        require(totalPoints >= 0) { "totalPoints must not be negative" }
        require(earnedThisYear >= 0) { "earnedThisYear must not be negative" }
        require(earnedThisYear <= totalPoints) { "earnedThisYear cannot exceed totalPoints" }
    }

    /**
     * Award [points] from [source]. Fails if the source is not in the catalogue of non-risky
     * earn events (invariant 1), if the party has not opted in (invariant 2), if the party is
     * in an adverse state (invariant 5), or if awarding would breach the yearly cap (invariant 4).
     */
    fun award(points: Int, yearlyRewardCap: Int, at: Instant): EngagementProfile {
        require(points > 0) { "points awarded must be positive" }
        require(enrolled) { "party has not opted in to the rewards hub (invariant 2)" }
        require(!adverseState) { "party is in an adverse state and excluded from engagement (invariant 5)" }
        require(earnedThisYear + points <= yearlyRewardCap) {
            "award would breach the yearly reward cap of $yearlyRewardCap (invariant 4)"
        }
        return copy(
            totalPoints = totalPoints + points,
            earnedThisYear = earnedThisYear + points,
            updatedAt = at,
        )
    }

    /** Unlock [badge] — silently idempotent (already-earned badges are not re-awarded). */
    fun unlock(badge: BadgeType, at: Instant): EngagementProfile {
        require(enrolled) { "party has not opted in (invariant 2)" }
        if (badge in badges) return this
        return copy(badges = badges + badge, updatedAt = at)
    }

    /** Record activity and advance the streak (or reset it if the gap exceeds one day). */
    fun recordActivity(at: Instant, maxGapSeconds: Long = STREAK_MAX_GAP_SECONDS): EngagementProfile {
        require(enrolled) { "party has not opted in (invariant 2)" }
        val newStreak = if (lastActivityAt != null && at.epochSecond - lastActivityAt.epochSecond <= maxGapSeconds) {
            streakDays + 1
        } else {
            1
        }
        return copy(streakDays = newStreak, lastActivityAt = at, updatedAt = at)
    }

    /** Opt in — keeps all prior earned value (invariant 2). */
    fun optIn(at: Instant): EngagementProfile = copy(enrolled = true, updatedAt = at)

    /** Opt out — earned value is kept (invariant 2: "leaving keeps earned value"). */
    fun optOut(at: Instant): EngagementProfile = copy(enrolled = false, updatedAt = at)

    companion object {
        const val STREAK_MAX_GAP_SECONDS = 25L * 3600 // 25-hour window for daily streaks (DST-safe)
    }
}

/**
 * ADR-0220 D3 invariant 1: the ONLY sources from which a point award is permitted. Any source
 * touching credit uptake, credit utilisation or risk-increasing behaviour is absent by design —
 * a new source is a PR to this enum, visible in every review.
 */
enum class EarnSource {
    /** A deposit to a savings goal (ADR-0153 metadata). */
    SAVINGS_DEPOSIT,

    /** Budget kept for a full week (PFM / Forecast screen). */
    BUDGET_KEPT,

    /** A recurring subscription cancelled (Vampires screen). */
    SUBSCRIPTION_CANCELLED,

    /** A standing-order repayment on an existing loan — on-time, not increasing the loan. */
    LOAN_REPAYMENT_ON_TIME,

    /** In-app educational content completed (when quests ship). */
    EDUCATIONAL_CONTENT,

    /** Daily login / app open — engagement baseline. */
    DAILY_ACTIVITY,
}

/** Badges the party can earn — each has a human-readable reason; no badge ever punishes. */
enum class BadgeType {
    FIRST_SAVINGS_GOAL,
    STREAK_7_DAYS,
    STREAK_30_DAYS,
    BUDGET_KEEPER,
    VAMPIRE_SLAYER,
    EARLY_ADOPTER,
    LOAN_FINISHER,
}

/**
 * A time-boxed challenge the party can complete for points (ADR-0220 D3). [expiresAt] is
 * nullable and non-null only for genuinely expiring items — invariant 3 is code, not policy.
 */
data class Challenge(
    val id: UUID,
    val partyId: UUID,
    val description: String,
    val earnSource: EarnSource,
    val pointsReward: Int,
    val expiresAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
) {
    init {
        require(pointsReward > 0) { "challenge reward must be positive" }
        require(description.isNotBlank()) { "challenge description must not be blank" }
    }

    val completed: Boolean get() = completedAt != null
    val expired: Boolean get() = expiresAt != null && Instant.now().isAfter(expiresAt)
}
