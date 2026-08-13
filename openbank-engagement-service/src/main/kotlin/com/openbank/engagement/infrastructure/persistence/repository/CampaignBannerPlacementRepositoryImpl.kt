// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.engagement.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.CampaignBannerPlacementRepository
import com.openbank.engagement.domain.model.CampaignBannerPlacement
import com.openbank.engagement.domain.model.SurfaceSlot
import com.openbank.engagement.infrastructure.persistence.entity.CampaignBannerPlacementEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class CampaignBannerPlacementRepositoryImpl(private val mapper: ObjectMapper) :
    CampaignBannerPlacementRepository,
    PanacheRepository<CampaignBannerPlacementEntity> {

    override suspend fun save(placement: CampaignBannerPlacement) {
        Panache.withTransaction {
            Panache.getSession().flatMap { it.merge(CampaignBannerPlacementEntity.from(placement, mapper)) }
        }.awaitSuspending()
    }

    override suspend fun latestForPartyAndSlot(partyId: UUID, slot: SurfaceSlot): CampaignBannerPlacement? =
        Panache.withSession {
            find("partyId = ?1 and slot = ?2 order by placedAt desc", partyId, slot.name).firstResult()
        }.awaitSuspending()?.toDomain(mapper)

    override suspend fun belongsToPartyAtSlot(interactionRef: UUID, partyId: UUID, slot: SurfaceSlot): Boolean =
        Panache.withSession {
            count("interactionRef = ?1 and partyId = ?2 and slot = ?3", interactionRef, partyId, slot.name)
        }.awaitSuspending() == 1L
}
