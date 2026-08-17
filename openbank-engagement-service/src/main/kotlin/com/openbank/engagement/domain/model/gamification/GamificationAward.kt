// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.model.gamification

import java.time.Instant
import java.util.UUID

/**
 * A single [Badge] earned once per party (`id` is a catalogue key, same closed-vocabulary
 * discipline as [ChallengeCatalog]).
 */
data class Badge(val id: String, val awardedAt: Instant)

/** A party's current consecutive-day count for one streak kind, and when it was last extended. */
data class Streak(val kind: String, val count: Int, val lastExtendedAt: Instant) {
    init {
        require(count >= 0) { "streak count must be non-negative, was $count" }
    }

    /** A streak either continues (called within the grace window by the caller) or resets to 1 — this
     *  function does not itself decide which; the caller supplies [continuing]. */
    fun extend(at: Instant, continuing: Boolean): Streak =
        copy(count = if (continuing) count + 1 else 1, lastExtendedAt = at)
}

/**
 * The audit-grade record of one [Points] award (task item: "a `GamificationAward` outbox-event
 * envelope needs `earnSourceId`, a rule/config version, correlation to the triggering domain
 * event, and explicit `actorType=SYSTEM` attribution").
 *
 * @param correlationEventId the id of the domain event (here, the persisted `EngagementEvent` row
 *   — see `EngagementEventRepository.save`'s return value) that triggered this award. Never a
 *   freshly minted id at award time: a correlation id that does not point at anything durable is
 *   not a correlation, it is decoration (same failure this repo has already paid for once with
 *   `Instant.EPOCH` timestamps nobody could act on).
 * @param ruleVersion [GamificationAwardRule.RULE_VERSION] at the moment of award — a named,
 *   reviewable constant, not derived from the service's own `version.txt` (which changes on
 *   unrelated releases and would silently misattribute historical awards to a rule version that
 *   never actually decided them).
 *
 * `actorType`/`actorId` attribution is NOT stored on this domain type — the SYSTEM attribution
 * (avoiding the #3994-class "unattributed audit row" defect) is an outbox/wire concern applied at
 * the infrastructure boundary via `com.openbank.libs.domain.event.EventActor`, the same shared
 * convention every other producer in this fleet uses; duplicating that vocabulary here would be a
 * second place for the "SYSTEM" spelling to drift from the canonical one.
 */
data class GamificationAward(
    val partyId: UUID,
    val challengeId: String,
    val earnSource: EarnSource,
    val points: Points,
    val ruleVersion: String,
    val correlationEventId: UUID,
    val occurredAt: Instant,
)

/**
 * Evaluates whether an already-recorded, catalogued [Challenge] completion earns [Points] for a
 * party. Deliberately takes a [Challenge] (never a raw earn-source id or points integer) so an
 * award can only ever be constructed from a reviewed catalogue entry — the same discipline
 * `SurfaceResolver` uses for content.
 */
object GamificationAwardRule {
    /** Bump when the award formula changes; every [GamificationAward] freezes the version that
     *  actually computed it, so a later change never silently reattributes history. */
    const val RULE_VERSION: String = "v1"

    fun award(challenge: Challenge, partyId: UUID, correlationEventId: UUID, occurredAt: Instant): GamificationAward =
        GamificationAward(
            partyId = partyId,
            challengeId = challenge.id,
            earnSource = challenge.earnSource,
            points = challenge.rewardPoints,
            ruleVersion = RULE_VERSION,
            correlationEventId = correlationEventId,
            occurredAt = occurredAt,
        )
}
