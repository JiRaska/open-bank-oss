// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.out.NotificationRequestPort
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import java.math.BigDecimal
import java.util.UUID

/**
 * Publishes notification requests onto `openbank.notification.requests` (the topic
 * notification-service's NotificationConsumer drains). Shape mirrors notification-service's
 * NotificationRequest: { partyId, channel, template, recipient, variables }.
 */
@ApplicationScoped
class KafkaNotificationRequestPublisher(
    private val objectMapper: ObjectMapper,
    @Channel("notification-requests-out") private val emitter: Emitter<Record<String, String>>,
) : NotificationRequestPort {

    override suspend fun notifyIncomingCredit(partyId: UUID, amount: BigDecimal, currency: String) {
        val request = mapOf(
            "partyId" to partyId.toString(),
            "channel" to "PUSH",
            "template" to "TRANSACTION_COMPLETED",
            // recipient is informational for PUSH (delivery is by registered device token).
            "recipient" to partyId.toString(),
            "variables" to mapOf(
                "amount" to amount.toPlainString(),
                "currency" to currency,
            ),
        )
        emitter.send(Record.of(partyId.toString(), objectMapper.writeValueAsString(request)))
    }
}
