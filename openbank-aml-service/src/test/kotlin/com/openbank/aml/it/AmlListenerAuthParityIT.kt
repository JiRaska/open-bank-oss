// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector

/** Real listener parity with AML's production mTLS and bearer configuration (#12511). */
@QuarkusTest
@TestProfile(AmlListenerAuthParityIT.Profile::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class AmlListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/aml/cases"

    class Profile : ProductionListenerProfile() {
        override fun extraOverrides() = mapOf(
            "authz.enforce" to "false",
            "quarkus.scheduler.enabled" to "false",
        )

        override fun extraResources() = listOf(
            io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry(InMemoryKafka::class.java),
        )
    }

    class InMemoryKafka : io.quarkus.test.common.QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("aml-events-out") +
                InMemoryConnector.switchIncomingChannelsToInMemory("party-events-in")

        override fun stop() = InMemoryConnector.clear()
    }
}
