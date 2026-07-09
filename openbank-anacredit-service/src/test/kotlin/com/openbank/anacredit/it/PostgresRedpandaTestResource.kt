// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated PostgreSQL + Redpanda (Kafka API) per test JVM (mirrors `balance-service`'s /
 * `party-service`'s `PostgresRedpandaTestResource`, CI infra sweep #578 idiom). anacredit-service now
 * boots the `@Incoming("lending-events-in")` consumer and the `loan_stage_projection` Flyway migration
 * (ADR-0037 follow-up, issue #638) — a real broker + DB are needed at boot, not an in-memory switch.
 */
class PostgresRedpandaTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<*>? = null
    private var redpanda: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank").withPassword("openbank_secret").withDatabaseName("openbank_anacredit_it")
        pg.start()
        postgres = pg
        val rp = RedpandaContainer(
            DockerImageName.parse("redpandadata/redpanda:v24.1.2")
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        rp.start()
        redpanda = rp
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        val bootstrap = rp.bootstrapServers
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_anacredit_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_anacredit_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "kafka.bootstrap.servers" to bootstrap,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to bootstrap,
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        redpanda?.stop()
        postgres?.stop()
    }
}
