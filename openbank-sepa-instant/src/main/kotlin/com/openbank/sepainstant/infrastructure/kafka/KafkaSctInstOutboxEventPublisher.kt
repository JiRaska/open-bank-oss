// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.infrastructure.kafka

import com.openbank.sepainstant.application.port.out.SctInstOutboxEventPublisher
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel

@ApplicationScoped
class KafkaSctInstOutboxEventPublisher(
    @Channel("sct-inst-events-out") private val emitter: MutinyEmitter<String>
) : SctInstOutboxEventPublisher {

    override suspend fun publish(payload: String) {
        emitter.send(payload).awaitSuspending()
    }
}
