// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.clearing.application.port.out.ClearingEventPublisher
import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingItem
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class ClearingEventPublisherImpl @Inject constructor(private val objectMapper: ObjectMapper) : ClearingEventPublisher {

    override fun publishBatchSettled(batch: ClearingBatch): Uni<Void> {
        // Stub: in production, emit to Kafka topic openbank.clearing.batch.settled
        return Uni.createFrom().voidItem()
    }

    override fun publishItemCleared(item: ClearingItem): Uni<Void> {
        // Stub: in production, emit to Kafka topic openbank.clearing.item.cleared
        return Uni.createFrom().voidItem()
    }
}
