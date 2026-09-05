// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/**
 * CI infra pilot (P2). Boots an isolated PostgreSQL + Redpanda (Kafka API) per test
 * JVM via Testcontainers and injects their dynamic URLs as the highest-precedence
 * Quarkus config, overriding the shared-stack localhost values from application.yaml.
 *
 * Why: the shared compose stack (one fixed-name postgres/kafka/valkey reused across
 * jobs, ADR-0043) flakes under full-fleet load — a job force-rebooting the stack, a
 * global Valkey FLUSHALL, or the Postgres bootstrap-restart race takes neighbours
 * down. A per-JVM container set has no shared state and no cross-job interference.
 *
 * Images are Docker Hub coordinates so the dind registry-mirror (the in-cluster
 * pull-through cache) serves them instead of egressing through the NAT Gateway;
 * Redpanda's canonical image lives on docker.redpanda.com, so we substitute the
 * Docker Hub mirror of the same image. Ryuk is disabled fleet-wide (the sandbox
 * runners can't grant a privileged reaper), so stop() tears the containers down.
 */
class PostgresRedpandaTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var redpanda: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_party_it")
        pg.start()
        postgres = pg
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")

        val rp = RedpandaContainer(
            DockerImageName.parse(REDPANDA_IMAGE)
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        try {
            rp.start()
        } catch (e: Exception) {
            pg.stop()
            postgres = null
            TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
            throw TestAbortedException("Redpanda failed to start — skipping IT: ${e.message}", e)
        }
        redpanda = rp
        TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "started")

        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        val bootstrap = rp.bootstrapServers

        return mapOf(
            // Reactive PG client (Hibernate Reactive Panache) + JDBC (Flyway migrations).
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_party_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_party_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            // SmallRye Kafka connector + the reactive-messaging Kafka default.
            "kafka.bootstrap.servers" to bootstrap,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to bootstrap,
            // Belt-and-suspenders: keep Quarkus Dev Services off; this resource owns infra.
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        redpanda?.let {
            it.stop()
            TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "stopped")
        }
        postgres?.let {
            it.stop()
            TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
        }
    }

    private companion object {
        const val POSTGRES_IMAGE = "postgres:16.3-alpine"
        const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
    }
}
