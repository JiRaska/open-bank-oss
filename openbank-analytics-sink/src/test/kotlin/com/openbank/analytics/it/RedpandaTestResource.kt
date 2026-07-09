// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated Redpanda (Kafka API) per test JVM via Testcontainers, injected as highest-precedence
 * config to override the shared-stack `localhost:29092` default. analytics-sink has an incoming
 * Kafka channel (`analytics-events-in`) that initialises at boot, so a real broker is needed for a
 * genuine `@QuarkusTest` boot — mirrors the pattern established for transaction-service (#578).
 * Docker Hub image -> served by the in-cluster registry-mirror; Ryuk disabled fleet-wide -> stop()
 * tears it down.
 */
class RedpandaTestResource : QuarkusTestResourceLifecycleManager {

    private var redpanda: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val rp = RedpandaContainer(
            DockerImageName.parse("redpandadata/redpanda:v24.1.2")
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        try {
            rp.start()
        } catch (e: Exception) {
            throw TestAbortedException("Redpanda failed to start — skipping IT: ${e.message}", e)
        }
        redpanda = rp

        val bootstrap = rp.bootstrapServers
        return mapOf(
            "kafka.bootstrap.servers" to bootstrap,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to bootstrap,
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        redpanda?.stop()
    }
}
