// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.persistence.entity

import com.openbank.engagement.domain.model.CampaignAttribution
import com.openbank.engagement.domain.model.EngagementEvent
import com.openbank.engagement.domain.model.EngagementEventType
import com.openbank.engagement.domain.model.SurfaceSlot
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Append-only — an engagement event is never updated after it is written. */
@Entity
@Table(name = "engagement_event")
class EngagementEventEntity : PanacheEntityBase() {
    @Id
    @Column(nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var partyId: UUID

    @Column(nullable = false)
    lateinit var contentId: String

    @Column(nullable = false)
    lateinit var slot: String

    @Column(nullable = false)
    lateinit var type: String

    @Column(nullable = false)
    lateinit var occurredAt: Instant

    /** Nullable so pre-attribution app events remain explicitly unattributed, never fabricated. */
    @Column
    var interactionRef: UUID? = null

    @Column
    var campaignId: UUID? = null

    @Column
    var campaignStepOrder: Int? = null

    @Column
    var campaignChannel: String? = null

    fun toDomain(): EngagementEvent = EngagementEvent(
        partyId = partyId,
        contentId = contentId,
        slot = SurfaceSlot.valueOf(slot),
        type = EngagementEventType.valueOf(type),
        occurredAt = occurredAt,
        interactionRef = interactionRef,
        campaignAttribution = campaignId?.let {
            CampaignAttribution(
                campaignId = it,
                stepOrder = requireNotNull(campaignStepOrder),
                channel = requireNotNull(campaignChannel),
            )
        },
    )

    companion object {
        fun from(event: EngagementEvent, id: UUID): EngagementEventEntity {
            val entity = EngagementEventEntity()
            entity.id = id
            entity.partyId = event.partyId
            entity.contentId = event.contentId
            entity.slot = event.slot.name
            entity.type = event.type.name
            entity.occurredAt = event.occurredAt
            entity.interactionRef = event.interactionRef
            entity.campaignId = event.campaignAttribution?.campaignId
            entity.campaignStepOrder = event.campaignAttribution?.stepOrder
            entity.campaignChannel = event.campaignAttribution?.channel
            return entity
        }
    }
}
