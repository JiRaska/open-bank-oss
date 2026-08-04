// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer

class PostgresTestResource : QuarkusTestResourceLifecycleManager {

    private lateinit var postgres: PostgreSQLContainer<*>

    override fun start(): Map<String, String> {
        postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("openbank_engagement")
            .withUsername("openbank")
            .withPassword("openbank")
        postgres.start()
        return mapOf(
            "quarkus.datasource.reactive.url" to
                "postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}",
            "quarkus.datasource.jdbc.url" to postgres.jdbcUrl,
            "quarkus.datasource.username" to postgres.username,
            "quarkus.datasource.password" to postgres.password,
        )
    }

    override fun stop() {
        if (::postgres.isInitialized) postgres.stop()
    }
}
