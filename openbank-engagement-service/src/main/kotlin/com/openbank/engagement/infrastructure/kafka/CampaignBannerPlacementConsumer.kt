// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.engagement.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.CampaignBannerPlacementRepository
import com.openbank.engagement.domain.model.CampaignBannerPlacement
import com.openbank.engagement.domain.model.SurfaceSlot
import com.openbank.libs.messaging.EventRetry
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
    suspend fun consume(payload: String) {
        // Parse first, and ack a malformed command: campaign's send log remains the durable audit
        // trail, and a replay of an unparseable command fails identically forever.
        val placement = parse(payload) ?: return

        // The write is the opposite case — a DB failure here is transient and the placement must
        // still land once it recovers, so it is retried and then rethrown for the DLQ (#5698).
        EventRetry.withRetry(log, "campaign banner placement for interaction ${placement.interactionRef}", null) {
            placements.save(placement)
        }
    }

    @Suppress("TooGenericExceptionCaught") // any parse/field failure on an untrusted payload is the
    // same unretryable poison pill, whatever Jackson or the enum lookup happens to throw.
    private fun parse(payload: String): CampaignBannerPlacement? =
        try {
            val node = mapper.readTree(payload)
            CampaignBannerPlacement(
                interactionRef = UUID.fromString(node.requiredText("interactionRef")),
                partyId = UUID.fromString(node.requiredText("partyId")),
                campaignId = UUID.fromString(node.requiredText("campaignId")),
                stepOrder = node.required("stepOrder").asInt(),
                template = node.requiredText("template"),
                values = mapper.convertValue(node.required("variables"), MAP_TYPE),
                deepLink = node.requiredText("deepLink"),
                placedAt = Instant.now(),
                // `inAppSurface` was added after HOME_BANNER shipped. Older campaign producers
                // legitimately omit it during a rolling deployment, so absence retains the
                // original slot; a present invalid value remains a malformed command.
                slot = node["inAppSurface"]?.let { slotNode ->
                    SurfaceSlot.entries.find { it.name == slotNode.asText().takeIf(String::isNotBlank) }
                        ?: error("unknown inAppSurface")
                } ?: SurfaceSlot.HOME_BANNER,
            )
        } catch (e: Exception) {
            // A malformed command cannot wedge the consumer group; campaign's send log remains
            // the durable audit trail and the error is visible for reconciliation.
            log.errorf(e, "Failed to parse campaign banner placement command: %.300s", payload)
            null
        }

    private fun com.fasterxml.jackson.databind.JsonNode.requiredText(name: String): String =
        required(name).asText().takeIf { it.isNotBlank() } ?: error("$name is required")

    companion object {
        private val MAP_TYPE = object : com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}
    }
}
