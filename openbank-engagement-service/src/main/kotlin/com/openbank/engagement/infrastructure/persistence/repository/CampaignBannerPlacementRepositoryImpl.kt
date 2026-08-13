// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.engagement.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.CampaignBannerPlacementRepository
import com.openbank.engagement.domain.model.CampaignBannerPlacement
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

    override suspend fun latestForParty(partyId: UUID): CampaignBannerPlacement? = Panache.withSession {
        find("partyId = ?1 order by placedAt desc", partyId).firstResult()
    }.awaitSuspending()?.toDomain(mapper)

    override suspend fun belongsToParty(interactionRef: UUID, partyId: UUID): Boolean = Panache.withSession {
        count("interactionRef = ?1 and partyId = ?2", interactionRef, partyId)
    }.awaitSuspending() == 1L
}
