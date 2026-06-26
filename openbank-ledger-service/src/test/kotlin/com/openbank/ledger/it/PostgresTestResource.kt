// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** CI infra sweep (#578). Isolated PostgreSQL per test JVM. For ITs whose Kafka
 *  channel is already switched to the in-memory connector (outbox dispatch tests). */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<*>? = null
    override fun start(): Map<String, String> {
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank").withPassword("openbank_secret").withDatabaseName("openbank_ledger_it")
        pg.start()
        postgres = pg
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_ledger_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_ledger_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.devservices.enabled" to "false",
        )
    }
    override fun stop() {
        postgres?.stop()
    }
}
