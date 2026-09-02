// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.containers

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated PostgreSQL + Redpanda (Kafka API) per test JVM — for ITs that boot a real
 * `@Channel` emitter/consumer without switching to the in-memory connector. See [PostgresBase]
 * for the database-name `initArgs` convention.
 */
class PostgresRedpandaTestResource : PostgresBase(RESOURCE_SCOPE_ID) {

    private var redpanda: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        val pg = startPostgres()

        val rp = RedpandaContainer(
            DockerImageName.parse("redpandadata/redpanda:v24.1.2")
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        rp.start()
        TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "started", resourceScopeId)
        redpanda = rp

        val bootstrap = rp.bootstrapServers
        return postgresConfig(pg) + mapOf(
            "kafka.bootstrap.servers" to bootstrap,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to bootstrap,
        )
    }

    override fun stop() {
        redpanda?.stop()
        if (redpanda != null) TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "stopped", resourceScopeId)
        super.stop()
    }

    private companion object {
        val RESOURCE_SCOPE_ID = Ids.randomId().toString()
        const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
    }
}
