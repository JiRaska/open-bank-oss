// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.CampaignEngagementEvent
import com.openbank.campaign.application.port.out.CampaignEngagementEventType
import com.openbank.campaign.application.port.out.CampaignEngagementRepository
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.InAppSurface
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * Builds the campaign-local, no-PII projection of attributable engagement events (#4480).
 *
 * The source topic also contains organic content events.  They are intentionally ignored: an
 * absent campaign tuple means "unattributed", not zero engagement and not a best-effort join to
 * a recent campaign.  Product conversion is likewise excluded; [ConversionConsumer] owns that
 * independently from authoritative banking events.
 */
@ApplicationScoped
class CampaignEngagementConsumer(
    private val mapper: ObjectMapper,
    private val engagement: CampaignEngagementRepository,
) {
    private val log = Logger.getLogger(CampaignEngagementConsumer::class.java)

    /** Reporting must never block a Kafka partition on an unrelated malformed app event. */
    @Suppress("TooGenericExceptionCaught")
    @Incoming("engagement-events-in")
    suspend fun onEvent(payload: String) {
        val event = runCatching { mapper.readTree(payload) }.getOrElse {
            log.errorf(it, "Unparseable engagement event — dropped from campaign projection")
            return
        }
        val attributable = event.toCampaignEngagementEvent() ?: return
        try {
            engagement.record(attributable)
        } catch (e: Exception) {
            // This is a read model, never a delivery decision.  Dropping a poisoned record keeps
            // campaign orchestration independent; monitoring sees the error and the UI must treat
            // an absent metric as unavailable, not as a zero.
            log.errorf(e, "Could not project engagement event %s", attributable.eventId)
        }
    }

    private fun JsonNode.toCampaignEngagementEvent(): CampaignEngagementEvent? {
        val campaignId = uuid("campaignId") ?: return null
        val eventId = uuid("eventId") ?: return null
        val stepOrder = path("stepOrder").takeIf { it.isInt }?.intValue()?.takeIf { it >= 0 } ?: return null
        val channel = named("channel", Channel.entries) ?: return null
        val surface = named("slot", InAppSurface.entries) ?: return null
        val type = named("type", CampaignEngagementEventType.entries) ?: return null
        val occurredAt = runCatching { Instant.parse(path("occurredAt").asText()) }.getOrNull() ?: return null
        return runCatching {
            CampaignEngagementEvent(eventId, campaignId, stepOrder, channel, surface, type, occurredAt)
        }.getOrNull()
    }

    private fun JsonNode.uuid(field: String): UUID? = path(field).asText()
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun <T : Enum<T>> JsonNode.named(field: String, values: Iterable<T>): T? = path(field).asText()
        .takeIf { it.isNotBlank() }
        ?.let { raw -> values.find { it.name == raw } }
}
