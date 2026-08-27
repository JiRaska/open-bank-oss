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
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/** Projects only attributed v2 reservation evidence; v1/operator events are intentionally ignored. */
@ApplicationScoped
class CampaignIncentiveOutcomeConsumer(
    private val mapper: ObjectMapper,
    private val projector: CampaignIncentiveOutcomeProjector,
) {
    private val log = Logger.getLogger(CampaignIncentiveOutcomeConsumer::class.java)

    @Incoming("incentive-events-in")
    suspend fun onEvent(payload: String) {
        val node = runCatching { mapper.readTree(payload) }.getOrElse {
            log.errorf(it, "Unparseable incentive event — dropped from campaign projection")
            return
        }
        node.toOutcome()?.let { projector.project(it) }
    }

    private fun JsonNode.toOutcome(): CampaignIncentiveOutcomeEvent? {
        val expectedStatus = expectedStatus(path("eventType").asText()) ?: return null
        val status = named("status", CampaignIncentiveOutcomeStatus.entries) ?: return null
        if (status != expectedStatus) return null
        return CampaignIncentiveOutcomeEvent(
            eventId = uuid("eventId") ?: return null,
            reservationId = uuid("reservationId") ?: return null,
            attributionRef = uuid("attributionRef") ?: return null,
            offerRef = path("offerRef").toOfferRef() ?: return null,
            status = status,
            occurredAt = runCatching { Instant.parse(path("occurredAt").asText()) }.getOrNull() ?: return null,
        )
    }

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
