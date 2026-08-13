// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.engagement.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.CampaignBannerPlacementRepository
import com.openbank.engagement.domain.model.CampaignBannerPlacement
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/** Consumes the campaign-owned command and stores the renderable first-party app placement. */
@ApplicationScoped
class CampaignBannerPlacementConsumer(
    private val placements: CampaignBannerPlacementRepository,
    private val mapper: ObjectMapper,
) {
    private val log = Logger.getLogger(CampaignBannerPlacementConsumer::class.java)

    @Incoming("campaign-banner-placements-in")
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(payload: String) {
        try {
            val node = mapper.readTree(payload)
            val placement = CampaignBannerPlacement(
                interactionRef = UUID.fromString(node.requiredText("interactionRef")),
                partyId = UUID.fromString(node.requiredText("partyId")),
                campaignId = UUID.fromString(node.requiredText("campaignId")),
                stepOrder = node.required("stepOrder").asInt(),
                template = node.requiredText("template"),
                values = mapper.convertValue(node.required("variables"), MAP_TYPE),
                deepLink = node.requiredText("deepLink"),
                placedAt = Instant.now(),
            )
            placements.save(placement)
        } catch (e: Exception) {
            // A malformed command cannot wedge the consumer group; campaign's send log remains
            // the durable audit trail and the error is visible for reconciliation.
            log.errorf(e, "Failed to place campaign banner: %.300s", payload)
        }
    }

    private fun com.fasterxml.jackson.databind.JsonNode.requiredText(name: String): String =
        required(name).asText().takeIf { it.isNotBlank() } ?: error("$name is required")

    companion object {
        private val MAP_TYPE = object : com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}
    }
}
