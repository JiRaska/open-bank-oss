// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Spins up a real PostgreSQL (Testcontainers) and points the datasource at it, so a @QuarkusTest
 * boot exercises Flyway + Hibernate + the reactive/JDBC drivers against a live DB — catching the
 * "released but never booted against real infra" defect class (missing driver, dup config key, bad
 * migration). Mirrors openbank-product-catalog's PostgresTestResource (ADR-0037 v2).
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withDatabaseName("openbank_anacredit")
            .withUsername("openbank")
            .withPassword("openbank")
        // Keep the handle before start: Quarkus calls stop() after a partial start too, where
        // lifecycle cleanup must remain visible in Test Intelligence evidence.
        postgres = pg
        pg.start()
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")
        val reactiveUrl = "postgresql://${pg.host}:${pg.firstMappedPort}/${pg.databaseName}"
        return mapOf(
            "quarkus.datasource.reactive.url" to reactiveUrl,
            "quarkus.datasource.jdbc.url" to pg.jdbcUrl,
            "quarkus.datasource.username" to pg.username,
            "quarkus.datasource.password" to pg.password,
        )
    }

    override fun stop() {
        postgres?.let {
            it.stop()
            TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
        }
    }

    private companion object {
        const val POSTGRES_IMAGE = "postgres:18-alpine"
    }
}
