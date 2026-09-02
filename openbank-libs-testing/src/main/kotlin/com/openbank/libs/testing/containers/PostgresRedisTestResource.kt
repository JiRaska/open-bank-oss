// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.containers

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated PostgreSQL + Valkey (Redis) per test JVM. See [PostgresBase] for the database-name
 * `initArgs` convention. A service that needs an additional test-only config override (e.g.
 * switching a specific Kafka channel to the in-memory connector) should set it via its own
 * `%test` `application.yaml` profile, not by subclassing this resource further.
 */
class PostgresRedisTestResource : PostgresBase(RESOURCE_SCOPE_ID) {

    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        val pg = startPostgres()

        val rd = GenericContainer(DockerImageName.parse("valkey/valkey:7.2-alpine")).withExposedPorts(REDIS_PORT)
        rd.start()
        TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "started", resourceScopeId)
        redis = rd

        return postgresConfig(pg) + mapOf(
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getFirstMappedPort()}",
        )
    }

    override fun stop() {
        redis?.stop()
        if (redis != null) TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "stopped", resourceScopeId)
        super.stop()
    }

    private companion object {
        val RESOURCE_SCOPE_ID = Ids.randomId().toString()
        const val REDIS_PORT = 6379
        const val VALKEY_IMAGE = "valkey/valkey:7.2-alpine"
    }
}
