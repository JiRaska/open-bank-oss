// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * An isolated PostgreSQL for [com.openbank.securityscanner.integration.IctIncidentDurabilityIT].
 *
 * A real database, not a mocked repository, is the whole point: the defect this module's IT exists
 * to pin is that the ICT incident register had no row at all, and only a real Flyway run + a real
 * INSERT can show that V4 creates `ict_incidents` and that an application-assigned UUID id
 * round-trips through `merge` without tripping the primary key on the second write.
 */
class SecurityScannerPostgresTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(
            DockerImageName.parse("docker.io/library/postgres:16.3-alpine")
                .asCompatibleSubstituteFor("postgres"),
        )
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName(DB)
        pg.start()
        postgres = pg
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")

        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/$DB",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/$DB",
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
        const val DB = "openbank_security_it"
        const val POSTGRES_IMAGE = "postgres:16.3-alpine"
    }
}
