// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated PostgreSQL + Valkey (Redis) per test JVM via Testcontainers. fraud-service now wires a
 * Redis-backed online feature store (ADR-0140), so a real Redis is needed at boot for the
 * `ReactiveRedisDataSource` `@Produces` (FeatureStoreConfig) to resolve and for the quarkus-redis
 * readiness probe to pass. The `transaction-signal` Kafka channel is disabled in `%test`, so no
 * broker is required.
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
            .withDatabaseName("openbank_fraud_it")
        pg.start()
        postgres = pg

        val rd = GenericContainer(DockerImageName.parse("valkey/valkey:7.2-alpine")).withExposedPorts(REDIS_PORT)
        rd.start()
        redis = rd

        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_fraud_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_fraud_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getMappedPort(REDIS_PORT)}",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        redis?.stop()
        postgres?.stop()
    }

    private companion object {
        const val REDIS_PORT = 6379
    }
}
