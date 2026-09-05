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
        send(partyId, "TRANSACTION_COMPLETED", mapOf("amount" to amount.toPlainString(), "currency" to currency))
    }

    override suspend fun notifyAccountOpened(partyId: UUID, accountNumber: String) =
        send(partyId, "ACCOUNT_OPENED", mapOf("accountNumber" to accountNumber))

    override suspend fun notifyAccountClosed(partyId: UUID, accountNumber: String) =
        send(partyId, "ACCOUNT_CLOSED", mapOf("accountNumber" to accountNumber))

    override suspend fun notifyAccountFrozen(partyId: UUID, accountNumber: String, reason: String) =
        send(partyId, "ACCOUNT_FROZEN", mapOf("accountNumber" to accountNumber, "reason" to reason))

    /**
     * The one place the envelope is built. Every variable map here must match the template's
     * declared `variables` set exactly — notification-service renders `${vars.v("x")}` and a
     * missing key surfaces to the customer as a hole in the message, not as an error here.
     */
    private fun send(partyId: UUID, template: String, variables: Map<String, String>) {
        val request = mapOf(
            "partyId" to partyId.toString(),
            "channel" to "PUSH",
            "template" to template,
            // recipient is informational for PUSH (delivery is by registered device token).
            "recipient" to partyId.toString(),
            "variables" to variables,
        )
        emitter.send(Record.of(partyId.toString(), objectMapper.writeValueAsString(request)))
    }
}
