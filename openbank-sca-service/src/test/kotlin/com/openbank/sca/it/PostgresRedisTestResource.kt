// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Per-job Testcontainers for sca-service integration and contract tests (ADR-0063 P2, issue #578).
 * Isolated PostgreSQL + Valkey (Redis) per test JVM injected as highest-precedence config.
 *
 * PostgreSQL: Flyway migrations run on boot (challenge/device/outbox tables).
 * Valkey: redis-client extension contributes a readiness health check, so a real instance is
 * needed for `/q/health/ready` to pass. The `sca-events-out` Kafka emitter is switched to
 * the in-memory connector (configured in test application.properties) — no broker needed.
 */
class PostgresRedisTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_sca_it")
        pg.start()
        postgres = pg

        val rd = GenericContainer(DockerImageName.parse("valkey/valkey:7.2-alpine")).withExposedPorts(6379)
        rd.start()
        redis = rd

        val pgHost = pg.host
        val pgPort = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to
                "vertx-reactive:postgresql://$pgHost:$pgPort/openbank_sca_it",
            "quarkus.datasource.jdbc.url" to
                "jdbc:postgresql://$pgHost:$pgPort/openbank_sca_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getFirstMappedPort()}",
            "mp.messaging.outgoing.sca-events-out.connector" to "smallrye-in-memory",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        redis?.stop()
        postgres?.stop()
    }
}
