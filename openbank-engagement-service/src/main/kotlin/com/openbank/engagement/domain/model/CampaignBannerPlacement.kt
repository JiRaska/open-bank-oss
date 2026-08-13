// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.engagement.domain.model

import java.time.Instant
import java.util.UUID

/** A campaign-approved card for the one supported campaign slot, the signed-in app home banner. */
data class CampaignBannerPlacement(
    val interactionRef: UUID,
    val partyId: UUID,
    val campaignId: UUID,
    val stepOrder: Int,
    val template: String,
    val values: Map<String, String>,
    val deepLink: String,
    val placedAt: Instant,
) {
    init {
        require(stepOrder >= 0) { "campaign step order must be non-negative" }
        require(template == PRODUCT_OFFER_BANNER_TEMPLATE) { "unknown campaign banner template '$template'" }
        require(values.keys.all { it in PRODUCT_OFFER_BANNER_VARIABLES }) { "banner has undeclared variables" }
        require(deepLink.startsWith("openbank://")) { "banner deep link must be an app route" }
    }

    fun toSurfaceContent(): SurfaceContent = SurfaceContent(
        id = CAMPAIGN_BANNER_CONTENT_ID,
        slot = SurfaceSlot.HOME_BANNER,
        type = SurfaceContentType.BANNER,
        variables = PRODUCT_OFFER_BANNER_VARIABLES,
        values = values,
        deepLink = deepLink,
        interactionRef = interactionRef,
    )

    companion object {
        const val CAMPAIGN_BANNER_CONTENT_ID = "CAMPAIGN_HOME_BANNER"
        const val PRODUCT_OFFER_BANNER_TEMPLATE = "MARKETING_PRODUCT_OFFER_BANNER"
        val PRODUCT_OFFER_BANNER_VARIABLES = setOf("offerTitle", "offerText", "ctaText")
    }
}
