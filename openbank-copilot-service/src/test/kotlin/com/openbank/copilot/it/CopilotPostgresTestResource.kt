// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Real Postgres for the erasure/retention ITs (#3870). Flyway runs against it, so the ITs exercise
 * the actual `conversation_history` table rather than a Hibernate-generated approximation.
 */
class CopilotPostgresTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var postgres: PostgreSQLContainer<*>

    override fun start(): Map<String, String> {
        postgres = PostgreSQLContainer(PGVECTOR_IMAGE)
            .withDatabaseName("openbank_copilot")
            .withUsername("openbank")
            .withPassword("openbank")
        postgres.start()
        TestInfrastructureEvidence.record("postgres", PGVECTOR_IMAGE.asCanonicalNameString(), "started")
        return mapOf(
            "quarkus.datasource.reactive.url" to
                "postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}",
            "quarkus.datasource.jdbc.url" to postgres.jdbcUrl,
            "quarkus.datasource.username" to postgres.username,
            "quarkus.datasource.password" to postgres.password,
            "quarkus.datasource.active" to "true",
            "quarkus.flyway.enabled" to "true",
            "quarkus.hibernate-orm.active" to "true",
        )
    }

    override fun stop() {
        if (::postgres.isInitialized) {
            postgres.stop()
            TestInfrastructureEvidence.record("postgres", PGVECTOR_IMAGE.asCanonicalNameString(), "stopped")
        }
    }
}

/**
 * Stock postgres plus pgvector, and nothing else — the V3 migration needs the `vector` extension or
 * Flyway aborts and every @QuarkusTest in this module fails with what looks like a Quarkus startup
 * error rather than a missing extension. `postgres:18-alpine` does not carry it. Same major version
 * as before, so nothing else about these tests changes.
 *
 * DockerImageName.asCompatibleSubstituteFor is required: Testcontainers checks the image name
 * against the module's expected one (`postgres`) and refuses an unrecognised repository outright.
 */
internal val PGVECTOR_IMAGE: org.testcontainers.utility.DockerImageName =
    org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg18")
        .asCompatibleSubstituteFor("postgres")
