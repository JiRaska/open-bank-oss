// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.util.Properties

/**
 * Isolated PostgreSQL + Valkey (Redis) + Redpanda (Kafka API) per test JVM via Testcontainers.
 * Unlike [PostgresRedisTestResource] (which leaves `transaction-signal` disabled in `%test`, so no
 * broker is needed for the plain boot-smoke/API tests), this resource ALSO re-enables the real
 * `transaction-signal` Kafka channel by overriding `mp.messaging.incoming.transaction-signal.enabled`
 * back to `true` and pointing it at a real Redpanda broker. This exists to reproduce the #NNN
 * cluster boot-crash (PR #635 fallout): the pre-existing boot-smoke test disables Kafka entirely, so
 * a crash that only manifests when `TransactionSignalConsumer.onTransactionInitiated` actually
 * processes a real Kafka message was never caught locally or in CI.
 */
class PostgresRedisRedpandaTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var redis: GenericContainer<*>? = null
    private var redpanda: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_fraud_it")
        pg.start()
        postgres = pg

        val rd = GenericContainer(DockerImageName.parse("valkey/valkey:7.2-alpine")).withExposedPorts(REDIS_PORT)
        rd.start()
        redis = rd

        val rp = RedpandaContainer(
            DockerImageName.parse("redpandadata/redpanda:v24.1.2")
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        try {
            rp.start()
        } catch (e: Exception) {
            throw TestAbortedException("Redpanda failed to start — skipping IT: ${e.message}", e)
        }
        redpanda = rp
        lastBootstrapServers = rp.bootstrapServers

        // Create the topic with multiple partitions BEFORE the app boots and subscribes — Redpanda
        // auto-creates topics with a single partition otherwise, which would serialize all message
        // processing onto one partition and mask any concurrency-dependent reactive-pool exhaustion
        // (the real cluster topic has multiple partitions, so worker threads genuinely overlap).
        AdminClient.create(
            Properties().apply { put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, rp.bootstrapServers) },
        ).use { admin ->
            admin.createTopics(
                listOf(NewTopic("openbank.transactions.transaction.initiated", TOPIC_PARTITIONS, 1)),
            ).all().get()
        }

        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_fraud_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_fraud_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getMappedPort(REDIS_PORT)}",
            "kafka.bootstrap.servers" to rp.bootstrapServers,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to rp.bootstrapServers,
            "mp.messaging.incoming.transaction-signal.enabled" to "true",
            "mp.messaging.incoming.transaction-signal.connector" to "smallrye-kafka",
            // Set here (a genuine runtime property source, not YAML) rather than in
            // application.yaml, for the same reason production sets them via env vars: SmallRye
            // Config's YAML source unconditionally quotes any leaf map key containing a literal
            // dot, so `group.id` written as a YAML key never resolves. A QuarkusTestResourceLifecycleManager's
            // returned Map does not go through that flattener, so this is the one place in the test
            // tree that can genuinely exercise the fixed group id end-to-end.
            "mp.messaging.incoming.transaction-signal.group.id" to "openbank-fraud-service-velocity",
            "quarkus.devservices.enabled" to "false",
            "quarkus.log.level" to "DEBUG",
            "quarkus.log.category.\"io.smallrye.reactive.messaging\".level" to "TRACE",
            "quarkus.log.category.\"io.vertx\".level" to "DEBUG",
            "quarkus.log.category.\"com.openbank.fraud\".level" to "TRACE",
            "quarkus.log.console.json" to "false",
        )
    }

    override fun stop() {
        redpanda?.stop()
        redis?.stop()
        postgres?.stop()
    }

    companion object {
        private const val REDIS_PORT = 6379

        /** Matches [com.openbank.fraud.integration.TransactionSignalConsumerBootIT]'s expectation. */
        const val TOPIC_PARTITIONS = 6

        /**
         * Set by [start] and read by test classes that need to produce directly to the broker
         * (e.g. [com.openbank.fraud.integration.TransactionSignalConsumerBootIT]). A static holder
         * is simplest here since [QuarkusTestResourceLifecycleManager] instances aren't injectable.
         */
        @Volatile
        var lastBootstrapServers: String? = null
            private set
    }
}
