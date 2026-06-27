// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.infrastructure.kafka

import com.openbank.security.application.port.out.SecurityOutboxEventPublisher
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import java.util.UUID

@ApplicationScoped
class KafkaSecurityOutboxEventPublisher(
    @Channel("security-events-out") private val emitter: Emitter<Record<String, String>>
) : SecurityOutboxEventPublisher {

    override suspend fun publish(payload: String) {
        emitter.send(Record.of(UUID.randomUUID().toString(), payload))
    }
}
