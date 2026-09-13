// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * A real PostgreSQL for the projection ITs, with Flyway running the service's own migrations.
 *
 * A real database is the point, not incidental infra: the defect in #6248 is a row that does not
 * change, and only a genuine INSERT/UPDATE against a genuine schema can distinguish "the
 * projection wrote it" from "a mock recorded the call". Ryuk is disabled fleet-wide, so `stop()`
 * is what tears the container down.
 */
class OnboardingPostgresTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName(DB)
        // Retain the handle before start so partial startup can still be cleaned up and observed.
        postgres = pg
        pg.start()
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")

        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/$DB",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/$DB",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.flyway.migrate-at-start" to "true",
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
        const val DB = "openbank_onboarding_it"
        const val POSTGRES_IMAGE = "postgres:16.3-alpine"
    }
}
