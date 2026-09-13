// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.GamificationAwardRepository
import com.openbank.engagement.domain.model.gamification.GamificationAward
import com.openbank.engagement.infrastructure.persistence.entity.GamificationAwardEntity
import com.openbank.libs.domain.event.EventActor
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Same split as `EngagementEventRepositoryImpl`: the award row and its outbox row are written in
 * the SAME transaction (ADR-0050), reusing [EngagementOutboxRepositoryImpl] rather than a second,
 * forked outbox mechanism — one dispatcher (`EngagementOutboxDispatcher`) drains both this
 * repository's rows and `EngagementEventRepositoryImpl`'s, exactly as `AbstractOutboxDispatcher`
 * is designed to.
 *
 * The outbox payload carries the audit-attribution shape the ADR-0220 D3 slice requires:
 * `earnSourceId`, `ruleVersion`, `correlationEventId` (the triggering `EngagementEvent` row's own
 * id), and explicit `actorType=SYSTEM`/`actorId=system:engagement:gamification-award-rule`
 * attribution via [EventActor] — the fleet's shared "no person originated this" vocabulary, never
 * a locally invented sentinel (the #3994-class defect this repo has already paid for once).
 */
@ApplicationScoped
class GamificationAwardRepositoryImpl(
    private val outbox: EngagementOutboxRepositoryImpl,
    private val mapper: ObjectMapper,
) : GamificationAwardRepository,
    PanacheRepository<GamificationAwardEntity> {

    override suspend fun alreadyAwarded(partyId: UUID, challengeId: String): Boolean = Panache.withSession {
        count("partyId = ?1 and challengeId = ?2", partyId, challengeId)
    }.map { it > 0 }.awaitSuspending()

    override suspend fun save(award: GamificationAward) {
        val awardId = Ids.newId()
        Panache.withTransaction {
            persist(GamificationAwardEntity.from(award, awardId)).chain { _ ->
                val payload = mapper.writeValueAsString(
                    buildMap {
                        put("awardId", awardId.toString())
                        put("aggregateType", "GAMIFICATION_AWARD")
                        put("aggregateId", awardId.toString())
                        put("partyId", award.partyId.toString())
                        put("challengeId", award.challengeId)
                        put("earnSourceId", award.earnSource.id)
                        put("points", award.points.value)
                        put("ruleVersion", award.ruleVersion)
                        put("correlationEventId", award.correlationEventId.toString())
                        put("occurredAt", award.occurredAt.toString())
                        put(EventActor.FIELD_ACTOR_TYPE, EventActor.TYPE_SYSTEM)
                        put(EventActor.FIELD_ACTOR_ID, EventActor.system(SERVICE_NAME, MECHANISM))
                    },
                )
                outbox.persistInTransaction(
                    OutboxMessage(
                        eventId = Ids.newId(),
                        aggregateId = award.partyId,
                        eventType = "GamificationAward.${award.challengeId}",
                        payload = payload,
                        createdAt = award.occurredAt,
                    ),
                )
            }
        }.awaitSuspending()
    }

    private companion object {
        const val SERVICE_NAME = "engagement"
        const val MECHANISM = "gamification-award-rule"
    }
}
