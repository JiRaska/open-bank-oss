// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Spins up a real PostgreSQL (Testcontainers) and points the datasource at it, so a @QuarkusTest
 * boot exercises Flyway + Hibernate + the reactive/JDBC drivers against a live DB — catching the
 * "released but never booted against real infra" defect class (missing driver, dup config key, bad
 * migration). Mirrors openbank-product-catalog's PostgresTestResource (ADR-0037 v2).
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {

    private lateinit var postgres: PostgreSQLContainer<*>

    override fun start(): Map<String, String> {
        postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("openbank_anacredit")
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
