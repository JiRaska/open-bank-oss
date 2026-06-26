// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root for details.

package com.openbank.consent.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.consent.application.port.out.ConsentEventPublisher
import com.openbank.consent.domain.event.*
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel

@ApplicationScoped
class KafkaConsentEventPublisher(
    @Channel("consent-events-out") private val emitter: MutinyEmitter<String>,
    private val objectMapper: ObjectMapper,
) : ConsentEventPublisher {

    override suspend fun publish(event: ConsentGranted) = send(event)
    override suspend fun publish(event: ConsentRevoked) = send(event)
    override suspend fun publish(event: ConsentExpired) = send(event)
    override suspend fun publish(event: ConsentRejected) = send(event)

    private suspend fun send(event: Any) {
        emitter.send(objectMapper.writeValueAsString(event)).awaitSuspending()
    }
}
