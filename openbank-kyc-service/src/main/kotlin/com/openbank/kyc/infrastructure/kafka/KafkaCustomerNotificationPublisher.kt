// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyc.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.port.out.CustomerNotificationPort
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import java.util.UUID

/**
 * Publishes KYC outcome notifications onto `openbank.notification.requests`, the topic
 * notification-service's `NotificationConsumer` drains.
 *
 * **A direct emitter, not the kyc outbox, and that is a deliberate trade.** This service's outbox
 * (`KycOutboxRepository` → `KafkaKycOutboxEventPublisher`) is single-destination: every entry goes
 * to `kyc-outbox-out`, and `OutboxMessage` carries no topic, so a notification request cannot ride
 * it without giving the shared outbox contract a routing concept. A direct emitter is also what
 * account-service, campaign-service and sca-service already do for this exact envelope.
 *
 * The cost is stated rather than hidden: a failed emit means the customer is not told, while the
 * KYC verdict stands. That asymmetry is the right way round — a notification hiccup must never undo
 * a compliance decision — but it does mean this path is best-effort, and #8432 records that a
 * durable version would need the outbox to learn destinations first.
 *
 * Envelope shape mirrors notification-service's `NotificationRequest`:
 * `{ partyId, channel, template, recipient, variables }`.
 */
@ApplicationScoped
class KafkaCustomerNotificationPublisher(
    private val objectMapper: ObjectMapper,
    @Channel("notification-requests-out") private val emitter: Emitter<Record<String, String>>,
) : CustomerNotificationPort {

    override suspend fun notifyKycApproved(partyId: UUID) = send(partyId, "KYC_APPROVED", emptyMap())

    override suspend fun notifyKycRejected(partyId: UUID, reason: String) =
        send(partyId, "KYC_REJECTED", mapOf("reason" to reason))

    private fun send(partyId: UUID, template: String, variables: Map<String, String>) {
        val request = mapOf(
            "partyId" to partyId.toString(),
            "channel" to "PUSH",
            "template" to template,
            // Informational for PUSH — delivery is by registered device token, not by this value.
            "recipient" to partyId.toString(),
            "variables" to variables,
        )
        // Keyed by party so a customer's notifications keep their order on one partition.
        emitter.send(Record.of(partyId.toString(), objectMapper.writeValueAsString(request)))
    }
}
