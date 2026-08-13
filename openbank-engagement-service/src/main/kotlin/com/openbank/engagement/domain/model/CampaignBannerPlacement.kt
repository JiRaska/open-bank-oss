// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.engagement.domain.model

import java.time.Instant
import java.util.UUID

/** A campaign-approved card for one closed authenticated-app surface. */
data class CampaignBannerPlacement(
    val interactionRef: UUID,
    val partyId: UUID,
    val campaignId: UUID,
    val stepOrder: Int,
    val template: String,
    val values: Map<String, String>,
    val deepLink: String,
    val placedAt: Instant,
    val slot: SurfaceSlot = SurfaceSlot.HOME_BANNER,
) {
    init {
        require(stepOrder >= 0) { "campaign step order must be non-negative" }
        require(template == templateFor(slot)) { "template '$template' does not render in $slot" }
        require(values.keys.all { it in PRODUCT_OFFER_BANNER_VARIABLES }) { "banner has undeclared variables" }
        require(deepLink.startsWith("openbank://")) { "banner deep link must be an app route" }
    }

    fun toSurfaceContent(): SurfaceContent = SurfaceContent(
        id = contentIdFor(slot),
        slot = slot,
        type = typeFor(slot),
        variables = PRODUCT_OFFER_BANNER_VARIABLES,
        values = values,
        deepLink = deepLink,
        interactionRef = interactionRef,
    )

    companion object {
        const val CAMPAIGN_BANNER_CONTENT_ID = "CAMPAIGN_HOME_BANNER"
        const val PRODUCT_OFFER_BANNER_TEMPLATE = "MARKETING_PRODUCT_OFFER_BANNER"
        val PRODUCT_OFFER_BANNER_VARIABLES = setOf("offerTitle", "offerText", "ctaText")

        fun templateFor(slot: SurfaceSlot): String = when (slot) {
            SurfaceSlot.HOME_BANNER -> PRODUCT_OFFER_BANNER_TEMPLATE
            SurfaceSlot.HOME_CAROUSEL -> "MARKETING_PRODUCT_OFFER_CAROUSEL"
            SurfaceSlot.PRODUCT_FEED -> "MARKETING_PRODUCT_OFFER_PRODUCT_FEED"
            SurfaceSlot.REWARDS_HUB -> "MARKETING_PRODUCT_OFFER_REWARDS_HUB"
            SurfaceSlot.STORIES -> error("campaign placements do not render in STORIES")
        }

        fun contentIdFor(slot: SurfaceSlot): String = "CAMPAIGN_${slot.name}"

        fun isCampaignContentId(contentId: String): Boolean = contentId in campaignSlots.map(::contentIdFor)

        private fun typeFor(slot: SurfaceSlot): SurfaceContentType = when (slot) {
            SurfaceSlot.HOME_BANNER -> SurfaceContentType.BANNER
            SurfaceSlot.HOME_CAROUSEL -> SurfaceContentType.CAROUSEL
            SurfaceSlot.PRODUCT_FEED, SurfaceSlot.REWARDS_HUB -> SurfaceContentType.CARD
            SurfaceSlot.STORIES -> error("campaign placements do not render in STORIES")
        }

        private val campaignSlots = setOf(
            SurfaceSlot.HOME_BANNER,
            SurfaceSlot.HOME_CAROUSEL,
            SurfaceSlot.PRODUCT_FEED,
            SurfaceSlot.REWARDS_HUB,
        )
    }
}
