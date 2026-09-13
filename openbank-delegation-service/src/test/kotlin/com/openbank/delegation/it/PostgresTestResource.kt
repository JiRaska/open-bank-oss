// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class PostgresTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var valkey: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("openbank_delegations_it")
            .withUsername("openbank")
            .withPassword("openbank_secret")
        val vk = GenericContainer(DockerImageName.parse(VALKEY_IMAGE))
            .withExposedPorts(6379)
        // Retain both handles before starting: stop() can then clean up and record a completed
        // lifecycle even when the second container's startup fails partway through.
        postgres = pg
        valkey = vk
        pg.start()
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")
        vk.start()
        TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "started")
        val pgHost = pg.host
        val pgPort = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        val db = pg.databaseName
        val redisPort = vk.getMappedPort(6379)
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
        valkey?.let {
            it.stop()
            TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "stopped")
        }
        postgres?.let {
            it.stop()
            TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
        }
    }

    private companion object {
        const val POSTGRES_IMAGE = "postgres:16-alpine"
        const val VALKEY_IMAGE = "docker.io/valkey/valkey:8-alpine"
    }
}
