// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.pid.application.port.out.PartyEventPublisher
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.Message

@ApplicationScoped
class KafkaPartyEventPublisher(
    @Channel("party-events-out") private val emitter: Emitter<String>,
    private val objectMapper: ObjectMapper,
) : PartyEventPublisher {

    override suspend fun publish(event: DomainEvent) {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to event.eventType,
                "aggregateId" to event.aggregateId,
                "occurredAt" to event.occurredAt.toString(),
                "payload" to event,
            ),
        )
        val metadata = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(event.aggregateId.toString())
            .build()
        emitter.send(Message.of(payload).addMetadata(metadata))
    }
}
