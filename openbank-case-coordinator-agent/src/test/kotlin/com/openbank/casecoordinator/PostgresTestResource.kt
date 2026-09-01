// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Spins up a real PostgreSQL (Testcontainers) and points the datasource at it, so the boot smoke
 * test exercises Flyway + Hibernate + the JDBC driver against a live DB — the check that catches the
 * "released but never booted" defect class (missing driver, dup config key, bad migration).
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {

    private companion object {
        const val POSTGRES_IMAGE = "postgres:18-alpine"
    }

    private lateinit var postgres: PostgreSQLContainer<*>

    override fun start(): Map<String, String> {
        postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("openbank_casecoordinator")
            .withUsername("openbank")
            .withPassword("openbank")
        postgres.start()
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")
        // reactive URL for Panache (vertx-pg-client) + JDBC URL for Flyway.
        val reactiveUrl = "postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}"
        return mapOf(
            "quarkus.datasource.reactive.url" to reactiveUrl,
            "quarkus.datasource.jdbc.url" to postgres.jdbcUrl,
            "quarkus.datasource.username" to postgres.username,
            "quarkus.datasource.password" to postgres.password,
        )
    }

    override fun stop() {
        if (::postgres.isInitialized) {
            postgres.stop()
            TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
        }
    }
}
