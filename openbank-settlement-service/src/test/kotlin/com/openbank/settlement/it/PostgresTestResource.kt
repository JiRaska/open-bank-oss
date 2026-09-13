// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * CI infra sweep (issue #578). Isolated PostgreSQL per test JVM via Testcontainers,
 * injected as highest-precedence config to override the shared-stack localhost values.
 * Hibernate Reactive + Flyway use real Postgres. The outbound emitter is in-memory here;
 * broker delivery is proved separately with a real Kafka test resource.
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null

    override fun start(): Map<String, String> {
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank")
            .withPassword(UUID.randomUUID().toString())
            .withDatabaseName("openbank_settlement_it")
        pg.start()
        postgres = pg
        TestInfrastructureEvidence.record("postgres", pg.dockerImageName, "started")
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to
                "vertx-reactive:postgresql://$host:$port/openbank_settlement_it",
            "quarkus.datasource.jdbc.url" to
                "jdbc:postgresql://$host:$port/openbank_settlement_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to pg.password,
            "quarkus.devservices.enabled" to "false",
            "mp.messaging.outgoing.settlement-events-out.connector" to "smallrye-in-memory",
        )
    }

    override fun stop() {
        postgres?.let {
            it.stop()
            TestInfrastructureEvidence.record("postgres", it.dockerImageName, "stopped")
        }
        postgres = null
    }
}
