// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sdd.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * CI infra sweep (issue #578). Isolated PostgreSQL per test JVM via Testcontainers,
 * injected as highest-precedence config to override the shared-stack localhost values.
 * Kafka is already switched to the in-memory connector in the IT (outbox dispatch),
 * so Postgres is the only remaining shared-stack dependency. Docker Hub image ->
 * served by the in-cluster registry-mirror; Ryuk disabled fleet-wide -> stop() cleans up.
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_sdd_it")
        pg.start()
        postgres = pg
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_sdd_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_sdd_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        postgres?.stop()
    }
}
