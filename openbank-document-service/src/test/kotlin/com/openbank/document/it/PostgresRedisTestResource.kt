// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class PostgresRedisTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE)
                .asCompatibleSubstituteFor("postgres"),
        )
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_documents_it")
        pg.start()
        postgres = pg
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")

        val rd = GenericContainer(DockerImageName.parse(VALKEY_IMAGE)).withExposedPorts(6379)
        try {
            rd.start()
        } catch (e: Exception) {
            pg.stop()
            postgres = null
            TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
            throw TestAbortedException("Valkey failed to start — skipping Testcontainers IT: ${e.message}", e)
        }
        redis = rd
        TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "started")

        val pgHost = pg.host
        val pgPort = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        val db = "openbank_documents_it"
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$pgHost:$pgPort/$db",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$pgHost:$pgPort/$db",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getFirstMappedPort()}",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        redis?.let {
            it.stop()
            TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "stopped")
        }
        postgres?.let {
            it.stop()
            TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
        }
    }

    private companion object {
        const val POSTGRES_IMAGE = "docker.io/library/postgres:16.3-alpine"
        const val VALKEY_IMAGE = "docker.io/valkey/valkey:7.2-alpine"
    }
}
