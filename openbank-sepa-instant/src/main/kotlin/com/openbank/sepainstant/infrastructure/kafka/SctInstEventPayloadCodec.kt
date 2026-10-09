// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.sepainstant.domain.event.SctInstEvent
import jakarta.enterprise.context.ApplicationScoped

/** The established four-field wire payload, shared by the old producer and durable writer. */
@ApplicationScoped
class SctInstEventPayloadCodec(private val objectMapper: ObjectMapper) {
    fun encode(event: SctInstEvent): String = objectMapper.writeValueAsString(
        mapOf(
            "type" to event::class.simpleName,
            "paymentId" to event.paymentId,
            "occurredAt" to event.occurredAt,
            "sourceService" to event.sourceService,
        ),
    )
}
