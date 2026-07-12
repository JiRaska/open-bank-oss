// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/** CI infra sweep (#578) idiom: isolated PostgreSQL + Redpanda (Kafka API) + Valkey (Redis) per
 *  test JVM. balance-service boots the @Channel("balance-outbox-out") emitter and two @Incoming
 *  consumers (ledger-events-in, balance-init-in) without an in-memory switch, so a real
 *  broker is needed at boot. Flyway runs against the container DB (V1..V8). Redis was added for
 *  ADR-0155's four-eyes ApprovalStore (issue #413) — quarkus-redis-client registers a readiness
 *  health check, so ANY test booting the full app via this resource now needs a reachable Redis
 *  or `/q/health/ready` reports DOWN (this bit BalanceApiIT/BalanceBootSmokeIT). */
class PostgresRedpandaTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<*>? = null
    private var redpanda: RedpandaContainer? = null
    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank").withPassword("openbank_secret").withDatabaseName("openbank_balances_it")
        pg.start()
        postgres = pg
        val rp = RedpandaContainer(
            DockerImageName.parse("redpandadata/redpanda:v24.1.2")
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        rp.start()
        redpanda = rp
        val rd = GenericContainer(DockerImageName.parse("valkey/valkey:7.2-alpine")).withExposedPorts(6379)
        rd.start()
        redis = rd
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        val bootstrap = rp.bootstrapServers
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_balances_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_balances_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "kafka.bootstrap.servers" to bootstrap,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to bootstrap,
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getFirstMappedPort()}",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        redis?.stop()
        redpanda?.stop()
        postgres?.stop()
    }
}
