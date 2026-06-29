// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Spins up a real PostgreSQL (Testcontainers) and points the datasource at it, so the @QuarkusTest
 * boot exercises Flyway + Hibernate + the JDBC/reactive drivers against a live DB — catching the
 * "released but never booted" defect class (missing driver, dup config key, bad migration).
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {

    private lateinit var postgres: PostgreSQLContainer<*>

    override fun start(): Map<String, String> {
        postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("openbank_products")
            .withUsername("openbank")
            .withPassword("openbank")
        postgres.start()
        val reactiveUrl = "postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}"
        return mapOf(
            "quarkus.datasource.reactive.url" to reactiveUrl,
            "quarkus.datasource.jdbc.url" to postgres.jdbcUrl,
            "quarkus.datasource.username" to postgres.username,
            "quarkus.datasource.password" to postgres.password,
        )
    }

    override fun stop() {
        if (::postgres.isInitialized) postgres.stop()
    }
}
