// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.engagement.infrastructure.persistence.entity

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.engagement.domain.model.CampaignBannerPlacement
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "campaign_banner_placement")
class CampaignBannerPlacementEntity : PanacheEntityBase() {
    @Id
    lateinit var interactionRef: UUID

    @Column(nullable = false)
    lateinit var partyId: UUID

    @Column(nullable = false)
    lateinit var campaignId: UUID

    @Column(nullable = false)
    var stepOrder: Int = 0

    @Column(nullable = false)
    lateinit var template: String

    @Column(nullable = false, columnDefinition = "text")
    lateinit var valuesJson: String

    @Column(nullable = false)
    lateinit var deepLink: String

    @Column(nullable = false)
    lateinit var placedAt: Instant

    fun toDomain(mapper: ObjectMapper) = CampaignBannerPlacement(
        interactionRef,
        partyId,
        campaignId,
        stepOrder,
        template,
        mapper.readValue(valuesJson),
        deepLink,
        placedAt,
    )

    companion object {
        fun from(placement: CampaignBannerPlacement, mapper: ObjectMapper) = CampaignBannerPlacementEntity().apply {
            interactionRef = placement.interactionRef
            partyId = placement.partyId
            campaignId = placement.campaignId
            stepOrder = placement.stepOrder
            template = placement.template
            valuesJson = mapper.writeValueAsString(placement.values)
            deepLink = placement.deepLink
            placedAt = placement.placedAt
        }
    }
}
