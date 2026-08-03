// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.NotificationSendPort
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import com.openbank.campaign.domain.model.Channel as CampaignChannel
import java.util.UUID

/**
 * ADR-0200 D3: delivery goes through notification-service, never direct. The payload shape mirrors
 * notification-service's `NotificationRequest` (partyId, channel, template, recipient, variables);
 * the template name resolves against the notification-service catalogue there, so an unknown
 * template fails closed at the choke point, not here.
 */
@ApplicationScoped
class KafkaNotificationSendAdapter(
    @Channel("notification-requests-out") private val emitter: Emitter<String>,
    private val mapper: ObjectMapper,
) : NotificationSendPort {

    override suspend fun requestSend(
        partyId: UUID,
        channel: CampaignChannel,
        template: String,
        recipient: String,
        variables: Map<String, String>,
    ) {
        val payload = mapper.writeValueAsString(
            mapOf(
                "partyId" to partyId.toString(),
                // Was hardcoded "EMAIL". A step's channel now travels with it — with the constant in place a
                // PUSH step was delivered as an email, silently, because notification-service believed
                // the payload over the campaign.
                "channel" to channel.name,
                "template" to template,
                "recipient" to recipient,
                "variables" to variables,
            ),
        )
        emitter.send(payload).toCompletableFuture().join()
    }
}
