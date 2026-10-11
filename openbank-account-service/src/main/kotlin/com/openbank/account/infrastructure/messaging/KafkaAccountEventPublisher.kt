// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.out.AccountEventPublisher
import com.openbank.account.domain.event.AccountClosedEvent
import com.openbank.account.domain.event.AccountCreatedEvent
import com.openbank.account.domain.event.AccountStatusChangedEvent
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

@ApplicationScoped
class KafkaAccountEventPublisher(
    private val objectMapper: ObjectMapper,
    @Channel("account-events-out") private val createdEmitter: Emitter<Record<String, String>>,
    @Channel("account-status-events-out") private val statusEmitter: Emitter<Record<String, String>>,
) : AccountEventPublisher {

    override suspend fun publish(topic: String, key: String, event: Any) {
        val emitter = when (event) {
            is AccountCreatedEvent -> {
                require(topic == CREATED_TOPIC) { "AccountCreated must use $CREATED_TOPIC" }
                createdEmitter
            }
            is AccountStatusChangedEvent, is AccountClosedEvent -> {
                require(topic == STATUS_TOPIC) { "Account status events must use $STATUS_TOPIC" }
                statusEmitter
            }
            else -> throw IllegalArgumentException("Unsupported account event type: ${event::class.java.name}")
        }
        val payload = objectMapper.writeValueAsString(event)
        emitter.send(Record.of(key, payload))
    }

    companion object {
        private const val CREATED_TOPIC = "openbank.accounts.account.created"
        private const val STATUS_TOPIC = "openbank.accounts.account.status-changed"
    }
}
