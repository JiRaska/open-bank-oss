// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.persistence.entity

import com.openbank.engagement.domain.model.gamification.GamificationAward
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Append-only ledger of every [GamificationAward] — never updated after insert, same convention
 * as `EngagementEventEntity`. The unique `(party_id, challenge_id)` index (V8 migration) is what
 * makes `alreadyAwarded` a real idempotency guard rather than a best-effort check: a duplicate
 * insert attempt fails at the database, not merely at the application layer that might race
 * itself under concurrent dispatch. Deliberately NOT `correlation_event_id` in that key — see the
 * migration's own comment for why keying on the triggering event id let the same one-time
 * challenge be paid out twice.
 */
@Entity
@Table(name = "gamification_award")
class GamificationAwardEntity : PanacheEntityBase() {
    @Id
    @Column(nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "party_id", nullable = false, updatable = false)
    lateinit var partyId: UUID

    @Column(name = "challenge_id", nullable = false, updatable = false)
    lateinit var challengeId: String

    @Column(name = "earn_source_id", nullable = false, updatable = false)
    lateinit var earnSourceId: String

    @Column(nullable = false, updatable = false)
    var points: Int = 0

    @Column(name = "rule_version", nullable = false, updatable = false)
    lateinit var ruleVersion: String

    @Column(name = "correlation_event_id", nullable = false, updatable = false)
    lateinit var correlationEventId: UUID

    @Column(name = "occurred_at", nullable = false, updatable = false)
    lateinit var occurredAt: Instant

    companion object {
        fun from(award: GamificationAward, id: UUID): GamificationAwardEntity {
            val entity = GamificationAwardEntity()
            entity.id = id
            entity.partyId = award.partyId
            entity.challengeId = award.challengeId
            entity.earnSourceId = award.earnSource.id
            entity.points = award.points.value
            entity.ruleVersion = award.ruleVersion
            entity.correlationEventId = award.correlationEventId
            entity.occurredAt = award.occurredAt
            return entity
        }
    }
}
