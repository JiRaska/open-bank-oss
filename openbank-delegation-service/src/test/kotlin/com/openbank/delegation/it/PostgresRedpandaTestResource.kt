// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/**
 * [PostgresTestResource] plus a real Redpanda (Kafka API) broker, for the ITs that drive an
 * `@Incoming` channel end to end rather than through the in-memory connector.
 */
class PostgresRedpandaTestResource : QuarkusTestResourceLifecycleManager {
    private val postgres = PostgresTestResource()
    private var redpanda: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        val base = postgres.start()
        val rp = RedpandaContainer(
            DockerImageName.parse(REDPANDA_IMAGE)
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        redpanda = rp
        rp.start()
        TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "started", containerId = rp.containerId)
        bootstrapServers = rp.bootstrapServers
        return base + mapOf(
            "kafka.bootstrap.servers" to rp.bootstrapServers,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to rp.bootstrapServers,
        )
    }

    override fun stop() {
        redpanda?.let {
            val containerId = it.containerId
            it.stop()
            TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "stopped", containerId = containerId)
        }
        postgres.stop()
    }

    companion object {
        private const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"

        @Volatile
        var bootstrapServers: String? = null
            private set
    }
}
