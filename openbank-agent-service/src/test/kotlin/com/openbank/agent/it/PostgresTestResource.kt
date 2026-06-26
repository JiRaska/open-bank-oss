// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.agent.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated PostgreSQL per test JVM (#578 pattern). agent-service is JDBC-only (Agroal), so only the
 * jdbc datasource is wired; Flyway migrates the schema into the throwaway container at boot.
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<*>? = null

    override fun start(): Map<String, String> {
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_agent_it")
        pg.start()
        postgres = pg
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_agent_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.devservices.enabled" to "false",
            // The model provider requires agent.model.openai.api-key at boot; the model is never
            // invoked by these HTTP smoke tests, so a placeholder lets the app start.
            "agent.model.openai.api-key" to "test-not-used",
        )
    }

    override fun stop() {
        postgres?.stop()
    }
}
