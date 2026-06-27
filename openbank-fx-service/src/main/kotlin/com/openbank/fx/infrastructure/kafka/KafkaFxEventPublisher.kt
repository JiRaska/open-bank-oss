// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.kafka

import com.openbank.fx.application.port.out.FxEventPublisher
import com.openbank.fx.domain.event.FxEvent
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class KafkaFxEventPublisher : FxEventPublisher {
    override suspend fun publish(event: FxEvent) {
        // Kafka publish stub — wire up SmallRye Reactive Messaging when broker is available
    }
}
