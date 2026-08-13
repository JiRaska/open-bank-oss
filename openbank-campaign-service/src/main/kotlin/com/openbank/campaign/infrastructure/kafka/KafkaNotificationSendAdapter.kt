// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.NotificationSendPort
import com.openbank.campaign.application.port.out.NotificationSendRequest
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

/**
 * ADR-0200 D3: delivery goes through notification-service, never direct. The payload shape mirrors
 * notification-service's `NotificationRequest` (partyId, channel, template, recipient, variables,
 * correlationId, interactionRef); the template name resolves against the notification-service catalogue there, so an
 * unknown template fails closed at the choke point, not here.
 *
 * The payload is hand-built as a map rather than shared as a type — the two services do not share a
 * module — so this map and that data class are two artifacts that can drift. Nothing in the build
 * compares them; what does is `NotificationRequestMessagePactConsumerTest` on the other side and the
 * AsyncAPI document, both of which have to be updated by hand when a field is added here.
 */
@ApplicationScoped
class KafkaNotificationSendAdapter(
    @Channel("notification-requests-out") private val emitter: Emitter<String>,
    private val mapper: ObjectMapper,
) : NotificationSendPort {

    override suspend fun requestSend(request: NotificationSendRequest) {
        val fields = linkedMapOf(
            "partyId" to request.partyId.toString(),
            // Was hardcoded "EMAIL". A step's channel now travels with it — with the constant in place a
            // PUSH step was delivered as an email, silently, because notification-service believed
            // the payload over the campaign.
            "channel" to request.channel.name,
            "template" to request.template,
            "recipient" to request.recipient,
            "variables" to request.variables,
            // ADR-0239 D1 — the send-log row id. notification-service echoes it back on
            // `openbank.notification.outcomes.v1`, which is the only way this service can learn
            // that a send it recorded as SENT was in fact suppressed or never delivered (#3663).
            "correlationId" to request.correlationId.toString(),
        )
        // This is transport metadata, never a template variable. The notification renderer's
        // closed variable schema therefore cannot accidentally interpolate a navigation route.
        request.deepLink?.let { fields["deepLink"] = it }
        // An opaque per-send reference for an eventual app interaction. It deliberately travels
        // beside the deep link rather than inside the template variables or customer content.
        request.interactionRef?.let { fields["interactionRef"] = it.toString() }
        val payload = mapper.writeValueAsString(fields)
        emitter.send(payload).toCompletableFuture().join()
    }
}
