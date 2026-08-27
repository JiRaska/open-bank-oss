// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.jboss.logging.Logger
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/**
 * CI infra sweep (issue #578). Isolated PostgreSQL + Redpanda (Kafka API) + Valkey (Redis)
 * per test JVM via Testcontainers, injected as highest-precedence config to override the
 * shared-stack localhost values. transaction-service has an outgoing Kafka channel
 * (@Channel("transaction-events-out") Emitter) that initialises at boot, so a real broker
 * is needed — Redpanda provides the Kafka API. Redis was added for ADR-0155's four-eyes
 * ApprovalStore (issue #413) — quarkus-redis-client registers a readiness health check, so
 * ANY test booting the full app via this resource now needs a reachable Redis or
 * `/q/health/ready` reports DOWN. Docker Hub images -> served by the in-cluster
 * registry-mirror; Ryuk disabled fleet-wide -> stop() tears them down.
 */
class PostgresRedpandaTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var redpanda: RedpandaContainer? = null
    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_transactions_it")
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
            throw TestAbortedException("Redpanda failed to start — skipping IT: ${e.message}", e)
        }
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
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_transactions_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_transactions_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "kafka.bootstrap.servers" to bootstrap,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to bootstrap,
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getFirstMappedPort()}",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        // Say out loud that the broker is going away (issue #5940). Quarkus calls stop() whenever
        // the set of @QuarkusTestResource classes changes between test classes, so this runs
        // mid-run, not only at the end — and any Kafka producer belonging to the previous boot is
        // still open and will start failing against a port that no longer exists. Without this
        // line the only evidence is a wall of "could not be established" with nothing explaining
        // what happened to the broker, which is exactly how #5940 read for 37 minutes.
        redpanda?.let { rp ->
            LOG.infof(
                "Stopping Redpanda test container (bootstrap=%s) — producers from the current " +
                    "Quarkus boot will fail to reach it after this point",
                rp.bootstrapServers,
            )
        }
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
        redis = null
        redpanda = null
        postgres = null
    }

    private companion object {
        const val POSTGRES_IMAGE = "postgres:16.3-alpine"
        const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
        const val VALKEY_IMAGE = "valkey/valkey:7.2-alpine"
        private val LOG: Logger = Logger.getLogger(PostgresRedpandaTestResource::class.java)
    }
}
