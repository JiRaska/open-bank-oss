// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.application.port.out.CustomerNotificationPort
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import java.math.BigDecimal
import java.util.UUID

/**
 * Publishes `TRANSACTION_FAILED` onto `openbank.notification.requests`, the topic
 * notification-service's `NotificationConsumer` drains.
 *
 * **A direct emitter rather than a consumer of this rail's own event topic, and the reason is not
 * the usual one.** `openbank.domestic.payment.events` already carries the rejection through the
 * transactional outbox, and consuming that would be more durable (the shape used for consent
 * notifications in #8491). It is not available here: `DomesticPaymentStatusChangedEvent` carries
 * `rejectReason` but **no amount, no currency and no owner party**, so a consumer could populate
 * neither the template's required variables nor its recipient without either joining against the
 * earlier created-event — a projection notification-service has no business holding — or calling
 * back synchronously from an event consumer. The account owner also has to be RESOLVED
 * (`AccountLookupPort.findPartyByAccountId`), which is I/O and cannot live in a pure event mapper.
 *
 * The cost is stated rather than hidden: a failed emit means the customer is not told, while the
 * rejection stands. That asymmetry is the right way round — a notification hiccup must never
 * disturb a payment verdict — but this path is best-effort, and #8432 records that a durable
 * version needs `amount`/`currency`/`ownerPartyId` added to the status-changed event first.
 *
 * Envelope shape mirrors notification-service's `NotificationRequest`:
 * `{ partyId, channel, template, recipient, variables }`.
 */
@ApplicationScoped
class KafkaCustomerNotificationPublisher(
    private val objectMapper: ObjectMapper,
    @Channel("notification-requests-out") private val emitter: Emitter<Record<String, String>>,
) : CustomerNotificationPort {

    override suspend fun notifyPaymentFailed(partyId: UUID, amount: BigDecimal, currency: String, reason: String) {
        val request = mapOf(
            "partyId" to partyId.toString(),
            "channel" to "PUSH",
            "template" to "TRANSACTION_FAILED",
            // Informational for PUSH — delivery is by registered device token, not by this value.
            "recipient" to partyId.toString(),
            "variables" to mapOf(
                "amount" to amount.toPlainString(),
                "currency" to currency,
                "reason" to reason,
            ),
        )
        // Keyed by party so a customer's notifications keep their order on one partition.
        emitter.send(Record.of(partyId.toString(), objectMapper.writeValueAsString(request)))
    }
}
