// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * CI infra sweep (issue #578). Boots an isolated PostgreSQL per test JVM via
 * Testcontainers and injects its dynamic URL as the highest-precedence Quarkus
 * config, overriding the shared-stack localhost values from application.yaml.
 *
 * notification-service only @Incoming-consumes Kafka (no @Outgoing), and that
 * channel is already switched to the in-memory connector in the test, so no Kafka
 * broker is needed — Postgres (NotificationRepository/Panache + Flyway) is the only
 * remaining shared-stack dependency. Image is a Docker Hub coord so the in-cluster
 * registry-mirror serves it; Ryuk is disabled fleet-wide so stop() tears it down.
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {

    private companion object {
        const val POSTGRES_IMAGE = "postgres:16.3-alpine"
    }

    private var postgres: PostgreSQLContainer<*>? = null

    override fun start(): Map<String, String> {
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_notification_it")
        pg.start()
        postgres = pg
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")

        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_notification_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_notification_it",
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
}
