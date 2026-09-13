// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * CI infra sweep (issue #578) — per-job Testcontainers. Isolated PostgreSQL + Valkey (Redis)
 * per test JVM, injected as highest-precedence config to override the shared-stack localhost
 * defaults.
 *
 * Both are needed for [com.openbank.psd2.integration.Psd2BootSmokeIT] to reach a green
 * `/q/health/ready`: Hibernate Reactive + Flyway need a real Postgres (the V1/V2 migrations run
 * on boot), and the redis-client extension contributes a readiness health check, so a real
 * Valkey is needed too. The service declares no outgoing Kafka channel since #8510 (the dead
 * outbox apparatus was deleted), so no broker or in-memory swap is required.
 *
 * Docker Hub coords -> served by the in-cluster registry-mirror; Ryuk is disabled fleet-wide ->
 * stop() tears the containers down.
 */
class PostgresRedisTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_psd2_it")
        pg.start()
        postgres = pg

        val rd = GenericContainer(DockerImageName.parse("valkey/valkey:7.2-alpine")).withExposedPorts(6379)
        rd.start()
        redis = rd

        val pgHost = pg.host
        val pgPort = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$pgHost:$pgPort/openbank_psd2_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$pgHost:$pgPort/openbank_psd2_it",
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
