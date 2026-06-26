// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.port.out.BalanceEventPublisher
import com.openbank.balance.domain.model.BalanceEvent
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

@ApplicationScoped
class KafkaBalanceEventPublisher(
    @Channel("balance-events-out") private val emitter: Emitter<String>,
    private val mapper: ObjectMapper,
) : BalanceEventPublisher {
    override suspend fun publish(event: BalanceEvent) {
        val json = mapper.writeValueAsString(event)
        emitter.send(json).toCompletableFuture().get()
    }
}
