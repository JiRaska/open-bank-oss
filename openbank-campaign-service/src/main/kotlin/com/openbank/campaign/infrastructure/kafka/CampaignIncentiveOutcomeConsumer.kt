// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.CampaignIncentiveOutcomeEvent
import com.openbank.campaign.application.port.out.CampaignIncentiveOutcomeStatus
import com.openbank.campaign.application.usecase.CampaignIncentiveOutcomeProjector
import com.openbank.campaign.domain.model.IncentiveOfferRef
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import java.time.Instant
import java.util.UUID

/** Projects only attributed v2 reservation evidence; v1/operator events are intentionally ignored. */
@ApplicationScoped
class CampaignIncentiveOutcomeConsumer(
    private val mapper: ObjectMapper,
    private val projector: CampaignIncentiveOutcomeProjector,
) {
    @Incoming("incentive-events-in")
    suspend fun onEvent(payload: String) {
        val node = runCatching { mapper.readTree(payload) }
            .getOrElse { throw IllegalArgumentException("Invalid incentive event JSON", it) }
        val eventType = node.path("eventType").asText()
        // This topic also carries legacy/operator evidence.  Only the four v2 lifecycle events are
        // this projection's contract; unknown types must not poison their shared partition.
        val expectedStatus = expectedStatus(eventType) ?: return
        projector.project(node.toOutcome(expectedStatus, eventType))
    }

    /** A recognized v2 event is all-or-nothing: malformed evidence must reach the configured DLQ. */
    private fun JsonNode.toOutcome(
        expectedStatus: CampaignIncentiveOutcomeStatus,
        eventType: String,
    ): CampaignIncentiveOutcomeEvent {
        val status = named("status", CampaignIncentiveOutcomeStatus.entries)
            ?: invalid(eventType, "status is absent or unknown")
        if (status != expectedStatus) invalid(eventType, "status $status does not match $expectedStatus")
        return CampaignIncentiveOutcomeEvent(
            eventId = uuid("eventId") ?: invalid(eventType, "eventId is invalid"),
            reservationId = uuid("reservationId") ?: invalid(eventType, "reservationId is invalid"),
            attributionRef = uuid("attributionRef") ?: invalid(eventType, "attributionRef is invalid"),
            offerRef = path("offerRef").toOfferRef() ?: invalid(eventType, "offerRef is invalid"),
            status = status,
            occurredAt = runCatching { Instant.parse(path("occurredAt").asText()) }.getOrNull()
                ?: invalid(eventType, "occurredAt is invalid"),
        )
    }

    private fun invalid(eventType: String, reason: String): Nothing =
        throw IllegalArgumentException("Malformed supported incentive event $eventType: $reason")

    private fun expectedStatus(eventType: String): CampaignIncentiveOutcomeStatus? = when (eventType) {
        "incentive.reservation.created.v2" -> CampaignIncentiveOutcomeStatus.RESERVED
        "incentive.reservation.committed.v2" -> CampaignIncentiveOutcomeStatus.COMMITTED
        "incentive.reservation.released.v2" -> CampaignIncentiveOutcomeStatus.RELEASED
        "incentive.reservation.expired.v2" -> CampaignIncentiveOutcomeStatus.EXPIRED
        else -> null
    }

    private fun JsonNode.toOfferRef(): IncentiveOfferRef? = runCatching {
        IncentiveOfferRef(
            id = uuid("id") ?: return null,
            name = path("name").asText().takeIf { it.isNotBlank() } ?: return null,
            version = path("version").takeIf { it.isInt }?.intValue()?.takeIf { it > 0 } ?: return null,
        )
    }.getOrNull()

    private fun JsonNode.uuid(field: String): UUID? = path(field).asText()
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun <T : Enum<T>> JsonNode.named(field: String, values: Iterable<T>): T? = path(field).asText()
        .takeIf { it.isNotBlank() }
        ?.let { raw -> values.find { it.name == raw } }
}
