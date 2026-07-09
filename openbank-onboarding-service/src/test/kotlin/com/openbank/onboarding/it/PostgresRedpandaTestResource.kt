// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.util.Properties

/**
 * Isolated PostgreSQL + Redpanda (Kafka API) per test JVM via Testcontainers.
 *
 * Modeled on openbank-fraud-service's `PostgresRedisRedpandaTestResource` (PR #685). Boots the app
 * with the three real `party-events-in` / `kyc-events-in` / `sca-events-in` channels enabled against
 * a real broker, so this is the one place in the test tree that can genuinely exercise the fixed
 * `group.id` end-to-end: a `QuarkusTestResourceLifecycleManager`'s returned Map is a genuine runtime
 * property source, not YAML, so it does not go through SmallRye Config's YAML-flattening dotted-key
 * quoting bug (the bug this fix addresses — see the comment on `application.yaml`'s
 * `mp.messaging.incoming.*` channels and issue #695).
 */
class PostgresRedpandaTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var redpanda: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_onboarding_it")
        pg.start()
        postgres = pg

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

        // Create the three topics up front so the consumers subscribe to a broker-known topic
        // rather than relying on auto-creation racing consumer startup.
        AdminClient.create(
            Properties().apply { put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, rp.bootstrapServers) },
        ).use { admin ->
            admin.createTopics(
                listOf(
                    NewTopic("openbank.party.events", 1, 1),
                    NewTopic("openbank.kyc.events", 1, 1),
                    NewTopic("openbank.sca.events", 1, 1),
                ),
            ).all().get()
        }

        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_onboarding_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_onboarding_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "kafka.bootstrap.servers" to rp.bootstrapServers,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to rp.bootstrapServers,
            // Set here (a genuine runtime property source, not YAML) rather than in
            // application.yaml, for the same reason production sets them via env vars: SmallRye
            // Config's YAML source unconditionally quotes any leaf map key containing a literal
            // dot, so `group.id` written as a YAML key never resolves.
            "mp.messaging.incoming.party-events-in.group.id" to EXPECTED_PARTY_GROUP_ID,
            "mp.messaging.incoming.party-events-in.auto.offset.reset" to "earliest",
            "mp.messaging.incoming.kyc-events-in.group.id" to EXPECTED_KYC_GROUP_ID,
            "mp.messaging.incoming.kyc-events-in.auto.offset.reset" to "earliest",
            "mp.messaging.incoming.sca-events-in.group.id" to EXPECTED_SCA_GROUP_ID,
            "mp.messaging.incoming.sca-events-in.auto.offset.reset" to "earliest",
            "quarkus.devservices.enabled" to "false",
            "quarkus.oidc.enabled" to "false",
            "quarkus.log.level" to "DEBUG",
            "quarkus.log.category.\"io.smallrye.reactive.messaging\".level" to "TRACE",
            "quarkus.log.console.json" to "false",
        )
    }

    override fun stop() {
        redpanda?.stop()
        postgres?.stop()
    }

    companion object {
        const val EXPECTED_PARTY_GROUP_ID = "onboarding-service-party"
        const val EXPECTED_KYC_GROUP_ID = "onboarding-service-kyc"
        const val EXPECTED_SCA_GROUP_ID = "onboarding-service-sca"

        /** Buggy fallback group id if group.id silently never resolved (quarkus.application.name). */
        const val BUGGY_FALLBACK_GROUP_ID = "openbank-onboarding-service"

        /**
         * Set by [start] and read by test classes that need to produce directly to the broker. A
         * static holder is simplest here since [QuarkusTestResourceLifecycleManager] instances
         * aren't injectable.
         */
        @Volatile
        var lastBootstrapServers: String? = null
            private set
    }
}
