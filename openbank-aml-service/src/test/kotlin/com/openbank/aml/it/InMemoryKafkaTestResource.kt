// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.smallrye.reactive.messaging.memory.InMemoryConnector

/** Every channel of the service on the in-memory connector, for ITs whose subject is not Kafka. */
class InMemoryKafkaTestResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> =
        InMemoryConnector.switchOutgoingChannelsToInMemory("aml-events-out", "oversight-events-out") +
            InMemoryConnector.switchIncomingChannelsToInMemory(
                "party-events-in",
                "ledger-events-in",
                "transaction-events-in",
            )

    override fun stop() = InMemoryConnector.clear()
}
