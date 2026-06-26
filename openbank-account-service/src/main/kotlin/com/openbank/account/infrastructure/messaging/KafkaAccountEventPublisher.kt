// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.account.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.out.AccountEventPublisher
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

@ApplicationScoped
class KafkaAccountEventPublisher(
    private val objectMapper: ObjectMapper,
    @Channel("account-events-out") private val emitter: Emitter<Record<String, String>>,
) : AccountEventPublisher {

    override suspend fun publish(topic: String, key: String, event: Any) {
        val payload = objectMapper.writeValueAsString(event)
        emitter.send(Record.of(key, payload))
    }
}
