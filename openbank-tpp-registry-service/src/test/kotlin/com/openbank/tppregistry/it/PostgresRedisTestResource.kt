// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * CI infra sweep (issue #578) — per-job Testcontainers. Isolated PostgreSQL + Valkey (Redis)
 * per test JVM, injected as highest-precedence config to override the shared-stack localhost
 * defaults.
 *
 * Both are needed for [com.openbank.tppregistry.integration.TppRegistryBootSmokeIT] to reach a
 * green `/q/health/ready`: Hibernate Reactive + Flyway need a real Postgres (the V1/V3/V4
 * migrations run on boot), and the redis-client extension contributes a readiness health check,
 * so a real Valkey is needed too. The single `@Channel("tpp-events-out")` Kafka emitter is
 * switched to the in-memory connector in the IT, so no broker container is required.
 *
 * Docker Hub coords -> served by the in-cluster registry-mirror; Ryuk is disabled fleet-wide ->
 * stop() tears the containers down.
 */
class PostgresRedisTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_tpp_registry_it")
        pg.start()
        postgres = pg
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")

        val rd = GenericContainer(DockerImageName.parse(VALKEY_IMAGE)).withExposedPorts(6379)
        rd.start()
        redis = rd
        TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "started")

        val pgHost = pg.host
        val pgPort = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$pgHost:$pgPort/openbank_tpp_registry_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$pgHost:$pgPort/openbank_tpp_registry_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getFirstMappedPort()}",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        try {
            redis?.let {
                it.stop()
                TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "stopped")
            }
        } finally {
            postgres?.let {
                it.stop()
                TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
            }
        }
    }

    private companion object {
        const val POSTGRES_IMAGE = "postgres:16.3-alpine"
        const val VALKEY_IMAGE = "valkey/valkey:7.2-alpine"
    }
}
