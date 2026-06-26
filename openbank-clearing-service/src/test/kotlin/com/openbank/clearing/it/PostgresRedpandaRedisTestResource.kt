// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.clearing.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/**
 * CI infra sweep (issue #578). Isolated PostgreSQL + Redpanda (Kafka API) + Valkey
 * (Redis) per test JVM via Testcontainers, injected as highest-precedence config to
 * override the localhost defaults. clearing-service boots the @Channel("clearing-events-out")
 * Kafka emitter (outbox dispatcher) without an in-memory switch, so a real broker is
 * needed at boot; the redis-client extension contributes a readiness health check, so
 * a real Valkey is needed for /q/health/ready. Ryuk is disabled fleet-wide ->
 * stop() tears the containers down.
 */
class PostgresRedpandaRedisTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var redpanda: RedpandaContainer? = null
    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }

        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_clearing_it")
        pg.start()
        postgres = pg

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

        val rd = GenericContainer(DockerImageName.parse("valkey/valkey:7.2-alpine")).withExposedPorts(6379)
        rd.start()
        redis = rd

        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        val bootstrap = rp.bootstrapServers
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_clearing_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_clearing_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "kafka.bootstrap.servers" to bootstrap,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to bootstrap,
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getFirstMappedPort()}",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        redis?.stop()
        redpanda?.stop()
        postgres?.stop()
    }
}
