// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/** CI infra sweep (#578). Isolated PostgreSQL + Redpanda (Kafka API) + Valkey (Redis) per test
 *  JVM. For ITs that boot the app's @Channel("ledger-events-out") emitter without an in-memory
 *  switch, so a real broker is needed (else they'd use the shared stack). Redis was added for
 *  ADR-0155's four-eyes ApprovalStore (issue #413) — quarkus-redis-client registers a readiness
 *  health check, so ANY test booting the full app via this resource now needs a reachable Redis
 *  or `/q/health/ready` reports DOWN (this bit LedgerApiIT's health-check assertion). */
class PostgresRedpandaTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<*>? = null
    private var redpanda: RedpandaContainer? = null
    private var redis: GenericContainer<*>? = null

    private companion object {
        const val POSTGRES_IMAGE = "postgres:16.3-alpine"
        const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
        const val VALKEY_IMAGE = "valkey/valkey:7.2-alpine"
    }

    override fun start(): Map<String, String> {
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withUsername("openbank").withPassword("openbank_secret").withDatabaseName("openbank_ledger_it")
        pg.start()
        postgres = pg
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")
        val rp = RedpandaContainer(
            DockerImageName.parse(REDPANDA_IMAGE)
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        rp.start()
        redpanda = rp
        TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "started")
        val rd = GenericContainer(DockerImageName.parse(VALKEY_IMAGE)).withExposedPorts(6379)
        rd.start()
        redis = rd
        TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "started")
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        val bootstrap = rp.bootstrapServers
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_ledger_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_ledger_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "kafka.bootstrap.servers" to bootstrap,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to bootstrap,
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getFirstMappedPort()}",
            "quarkus.devservices.enabled" to "false",
        )
    }
    override fun stop() {
        redis?.let {
            it.stop()
            TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "stopped")
        }
        redpanda?.let {
            it.stop()
            TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "stopped")
        }
        postgres?.let {
            it.stop()
            TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
        }
    }
}
