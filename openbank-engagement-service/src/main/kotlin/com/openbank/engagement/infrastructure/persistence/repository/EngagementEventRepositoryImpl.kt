// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.persistence.repository

import com.openbank.engagement.application.port.out.EngagementEventRepository
import com.openbank.engagement.domain.model.EngagementEvent
import com.openbank.engagement.domain.model.SurfaceSlot
import com.openbank.engagement.infrastructure.persistence.entity.EngagementEventEntity
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class EngagementEventRepositoryImpl(
    private val outbox: EngagementOutboxRepositoryImpl,
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper,
) : EngagementEventRepository,
    PanacheRepository<EngagementEventEntity> {

    /**
     * The event row and its outbox row are written in the SAME transaction (ADR-0050): a crash
     * between the two would otherwise either lose the event or publish one that was never
     * actually recorded. `Ids.newId()` (UUIDv7, ADR-0106) for both — durable, indexed rows.
     */
    override suspend fun save(event: EngagementEvent) {
        val eventId = Ids.newId()
        Panache.withTransaction {
            persist(EngagementEventEntity.from(event, eventId)).chain { _ ->
                val payload = mapper.writeValueAsString(
                    buildMap {
                        put("eventId", eventId.toString())
                        put("partyId", event.partyId.toString())
                        put("contentId", event.contentId)
                        put("slot", event.slot.name)
                        put("type", event.type.name)
                        put("occurredAt", event.occurredAt.toString())
                        event.interactionRef?.let { put("interactionRef", it.toString()) }
                    },
                )
                outbox.persistInTransaction(
                    OutboxMessage(
                        // UUIDv7 (ADR-0106): the outbox row is durable and indexed, same reason
                        // as the event row's own id — not the v4 randomId() I used here first,
                        // which is for idempotency keys/nonces, not indexed primary keys.
                        eventId = Ids.newId(),
                        aggregateId = event.partyId,
                        eventType = "EngagementEvent.${event.type.name}",
                        payload = payload,
                        createdAt = event.occurredAt,
                    ),
                )
            }
        }.awaitSuspending()
    }

    override suspend fun recentForPartyAndSlot(
        partyId: UUID,
        slot: SurfaceSlot,
        since: Instant,
    ): List<EngagementEvent> = Panache.withSession {
        find(
            "partyId = ?1 and slot = ?2 and occurredAt >= ?3 order by occurredAt asc",
            partyId,
            slot.name,
            since,
        ).list()
    }.map { entities -> entities.map { it.toDomain() } }.awaitSuspending()

    override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant): Int = Panache.withSession {
        count(
            "partyId = ?1 and type = ?2 and occurredAt >= ?3",
            partyId,
            "IMPRESSION",
            windowStart,
        )
    }.map { it.toInt() }.awaitSuspending()
}
