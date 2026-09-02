// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated PostgreSQL per test JVM (#578 pattern). agent-service is JDBC-only (Agroal), so only the
 * jdbc datasource is wired; Flyway migrates the schema into the throwaway container at boot.
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<*>? = null

    companion object {
        private const val POSTGRES_IMAGE = "postgres:16.3-alpine"
    }

    override fun start(): Map<String, String> {
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_agent_it")
        pg.start()
        postgres = pg
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")
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
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
    }
}
