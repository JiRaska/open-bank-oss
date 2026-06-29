// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Handles PARTY_ERASED events from party-service (GDPR Art. 17 / ADR-0117):
 * - Deletes all device tokens for the party so push delivery stops immediately.
 * - Deletes all notification records (content is PII — message body, recipient address).
 *
 * Poison-pill safe: failures are logged and acked.
 */
@ApplicationScoped
class PartyErasureConsumer {

    @Inject
    lateinit var deviceTokenRepository: DeviceTokenRepository

    @Inject
    lateinit var notificationRepository: NotificationRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(PartyErasureConsumer::class.java)

    @Incoming("party-events-in")
    suspend fun consume(payload: String) {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to parse JSON: %.200s", payload)
            return
        }

        if (node.path("eventType").asText() != "PARTY_ERASED") return

        val partyId = runCatching { UUID.fromString(node.path("partyId").asText()) }.getOrNull()
        if (partyId == null) {
            log.warnf("[party-events-in] PARTY_ERASED without valid partyId, skipping: %.200s", payload)
            return
        }

        try {
            val tokens = deviceTokenRepository.deleteByPartyId(partyId)
            val notifications = notificationRepository.deleteByPartyId(partyId)
            log.infof(
                "[party-events-in] GDPR Art. 17: deleted %d device tokens and %d notifications for party %s",
                tokens,
                notifications,
                partyId,
            )
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to erase notification data for party %s", partyId)
        }
    }
}
