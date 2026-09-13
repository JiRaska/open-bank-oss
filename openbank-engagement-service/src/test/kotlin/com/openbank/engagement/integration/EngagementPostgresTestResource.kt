// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.integration

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated Postgres for the surface REST IT, same shape as `CampaignPostgresRedisTestResource`
 * minus Redis (this service has no Redis-backed feature). Hibernate Reactive + Flyway want a real
 * Postgres to reach a state where the app answers HTTP at all.
 */
class EngagementPostgresTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null

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
            .withDatabaseName("openbank_engagement_it")
        pg.start()
        postgres = pg
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")

        return mapOf(
            "quarkus.datasource.reactive.url" to
                "vertx-reactive:postgresql://${pg.host}:${pg.getMappedPort(
                    PostgreSQLContainer.POSTGRESQL_PORT,
                )}/openbank_engagement_it",
            "quarkus.datasource.jdbc.url" to
                "jdbc:postgresql://${pg.host}:${pg.getMappedPort(
                    PostgreSQLContainer.POSTGRESQL_PORT,
                )}/openbank_engagement_it",
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
        const val POSTGRES_IMAGE = "docker.io/library/postgres:16.3-alpine"
    }
}
