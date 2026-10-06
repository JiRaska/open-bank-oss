// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.kafka

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Channel

@ApplicationScoped
class KafkaSctInstEventPublisher @Inject constructor(
    @Channel("sct-inst-events-out") private val emitter: MutinyEmitter<String>,
) : OutboxEventPublisher {

    /** Outbox relay keeps the original unkeyed Kafka record and exact stored payload. */
    override suspend fun publish(entry: OutboxEntry) {
        emitter.send(entry.payload).awaitSuspending()
    }
}
