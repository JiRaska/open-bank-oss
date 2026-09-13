// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * CI infra sweep (issue #578). Isolated PostgreSQL per test JVM via Testcontainers,
 * injected as highest-precedence config to override the shared-stack localhost values.
 * pid-service configures no Redis, and both Kafka emitters are switched to the in-memory
 * connector in the IT, so Postgres is the only remaining shared-stack dependency: Hibernate
 * Reactive + Flyway need a real Postgres (the V1..V5 migrations run on boot). Docker Hub image
 * -> served by the in-cluster registry-mirror; Ryuk disabled fleet-wide -> stop() cleans up.
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_pid_it")
        // Keep the handle before start() so a partial container startup remains observable and
        // stop() can perform the same cleanup guarantee as a successful boot.
        postgres = pg
        pg.start()
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_pid_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_pid_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        postgres?.let {
            it.stop()
            TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
        }
    }

    private companion object {
        const val POSTGRES_IMAGE = "postgres:16.3-alpine"
    }
}
