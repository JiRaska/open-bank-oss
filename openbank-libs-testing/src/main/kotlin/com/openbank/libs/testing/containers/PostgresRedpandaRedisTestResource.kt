// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.containers

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import org.testcontainers.containers.GenericContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated PostgreSQL + Redpanda (Kafka API) + Valkey (Redis) per test JVM — for a service that
 * boots a real `@Channel`/`@Incoming` Kafka connector at startup AND depends on Redis (e.g. a
 * redis-client readiness health check, or a Redis-backed store). See [PostgresBase] for the
 * database-name `initArgs` convention. Consolidates three near-identical fleet copies
 * (account-service, clearing-service, sepa-instant — issue #467 CI infra sweep follow-up).
 */
class PostgresRedpandaRedisTestResource : PostgresBase(RESOURCE_SCOPE_ID) {

    private var redpanda: RedpandaContainer? = null
    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        val pg = startPostgres()

        val rp = RedpandaContainer(
            DockerImageName.parse("redpandadata/redpanda:v24.1.2")
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        rp.start()
        TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "started", resourceScopeId)
        redpanda = rp

        val rd = GenericContainer(DockerImageName.parse(VALKEY_IMAGE)).withExposedPorts(REDIS_PORT)
        rd.start()
        TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "started", resourceScopeId)
        redis = rd

        val bootstrap = rp.bootstrapServers
        return postgresConfig(pg) + mapOf(
            "kafka.bootstrap.servers" to bootstrap,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to bootstrap,
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getFirstMappedPort()}",
        )
    }

    override fun stop() {
        redis?.stop()
        if (redis != null) TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "stopped", resourceScopeId)
        redpanda?.stop()
        if (redpanda != null) TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "stopped", resourceScopeId)
        super.stop()
    }

    private companion object {
        val RESOURCE_SCOPE_ID = Ids.randomId().toString()
        const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
        const val VALKEY_IMAGE = "valkey/valkey:7.2-alpine"
        const val REDIS_PORT = 6379
    }
}
