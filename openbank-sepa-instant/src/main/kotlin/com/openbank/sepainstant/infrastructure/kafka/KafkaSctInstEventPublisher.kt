// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.sepainstant.application.port.out.SctInstEventPublisher
import com.openbank.sepainstant.domain.event.SctInstEvent
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Channel

@ApplicationScoped
class KafkaSctInstEventPublisher @Inject constructor(
    @Channel("sct-inst-events-out") private val emitter: MutinyEmitter<String>,
    private val objectMapper: ObjectMapper
) : SctInstEventPublisher {

    override fun publish(event: SctInstEvent): Uni<Void> =
        emitter.send(objectMapper.writeValueAsString(mapOf(
            "type" to event::class.simpleName,
            "paymentId" to event.paymentId,
            "occurredAt" to event.occurredAt,
            // Issue #3994/#5256: read by AuditConsumer.resolveSourceService as the strongest
            // (EVENT-sourced) attribution. This publisher builds a HAND-BUILT map (not a
            // serialised data class), so sourceService must be added to the map explicitly — a
            // field on SctInstEvent alone would never reach the wire here.
            "sourceService" to event.sourceService
        )))
}
