// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class PostgresTestResource : QuarkusTestResourceLifecycleManager {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var valkey: GenericContainer<*>

    override fun start(): Map<String, String> {
        postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("openbank_delegations_it")
            .withUsername("openbank")
            .withPassword("openbank_secret")
        valkey = GenericContainer(DockerImageName.parse("docker.io/valkey/valkey:8-alpine"))
            .withExposedPorts(6379)
        postgres.start()
        valkey.start()
        val pgHost = postgres.host
        val pgPort = postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        val db = postgres.databaseName
        val redisPort = valkey.getMappedPort(6379)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$pgHost:$pgPort/$db",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$pgHost:$pgPort/$db",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.redis.hosts" to "redis://localhost:$redisPort",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        valkey.stop()
        postgres.stop()
    }
}
