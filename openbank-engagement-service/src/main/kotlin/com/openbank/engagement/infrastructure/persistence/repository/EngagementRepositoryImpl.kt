// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.engagement.application.port.out.EngagementOutboxRepository
import com.openbank.engagement.application.port.out.EngagementRepository
import com.openbank.engagement.domain.model.BadgeType
import com.openbank.engagement.domain.model.EngagementProfile
import com.openbank.engagement.infrastructure.persistence.entity.EngagementProfileEntity
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class EngagementRepositoryImpl(private val outbox: EngagementOutboxRepository, private val mapper: ObjectMapper) :
    EngagementRepository,
    PanacheRepository<EngagementProfileEntity> {

    override suspend fun findByParty(partyId: UUID): EngagementProfile? =
        Panache.withSession { find("partyId", partyId).firstResult<EngagementProfileEntity>() }
            .awaitSuspending()?.toDomain()

    override suspend fun save(profile: EngagementProfile): EngagementProfile = Panache.withTransaction {
        Panache.getSession().flatMap { s -> s.merge(profile.toEntity()) }
    }.awaitSuspending().let { profile }

    override suspend fun save(profile: EngagementProfile, event: DomainEvent): EngagementProfile =
        Panache.withTransaction {
            Panache.getSession().flatMap { s -> s.merge(profile.toEntity()) }
                .flatMap { _ -> outbox.persistInTransaction(event.toMessage()).replaceWith(profile) }
        }.awaitSuspending()

    private fun DomainEvent.toMessage() = OutboxMessage(
        aggregateId = aggregateId,
        eventType = eventType,
        payload = mapper.writeValueAsString(this),
        createdAt = occurredAt,
    )

    private fun EngagementProfile.toEntity(): EngagementProfileEntity = EngagementProfileEntity().apply {
        partyId = this@toEntity.partyId
        enrolled = this@toEntity.enrolled
        adverseState = this@toEntity.adverseState
        streakDays = this@toEntity.streakDays
        lastActivityAt = this@toEntity.lastActivityAt
        totalPoints = this@toEntity.totalPoints
        earnedThisYear = this@toEntity.earnedThisYear
        badgesJson = mapper.writeValueAsString(this@toEntity.badges.map { it.name })
        createdAt = this@toEntity.createdAt
        updatedAt = this@toEntity.updatedAt
    }

    private fun EngagementProfileEntity.toDomain(): EngagementProfile = EngagementProfile(
        partyId = partyId,
        enrolled = enrolled,
        adverseState = adverseState,
        streakDays = streakDays,
        lastActivityAt = lastActivityAt,
        totalPoints = totalPoints,
        earnedThisYear = earnedThisYear,
        badges = mapper.readValue<List<String>>(badgesJson).map { BadgeType.valueOf(it) }.toSet(),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
