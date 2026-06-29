// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated PostgreSQL + Valkey (Redis) containers for [com.openbank.consent.integration.ConsentBootSmokeIT].
 *
 * Both are required for a green `/q/health/ready`: Hibernate Reactive + Flyway need a real
 * Postgres (V1..VN migrations run on boot); the redis-client extension contributes a readiness
 * health check so a real Valkey is needed too. The outgoing Kafka emitter
 * (`consent-events-out`) is switched to the in-memory connector in the IT — no broker needed.
 */
class ConsentPostgresRedisTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(
            DockerImageName.parse("docker.io/library/postgres:16.3-alpine")
                .asCompatibleSubstituteFor("postgres"),
        )
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_consents_it")
        pg.start()
        postgres = pg

        val rd = GenericContainer(DockerImageName.parse("docker.io/valkey/valkey:7.2-alpine")).withExposedPorts(6379)
        rd.start()
        redis = rd

        val pgHost = pg.host
        val pgPort = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to
                "vertx-reactive:postgresql://$pgHost:$pgPort/openbank_consents_it",
            "quarkus.datasource.jdbc.url" to
                "jdbc:postgresql://$pgHost:$pgPort/openbank_consents_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getFirstMappedPort()}",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        redis?.stop()
        postgres?.stop()
    }
}
