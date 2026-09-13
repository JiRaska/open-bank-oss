// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated PostgreSQL + Redpanda (Kafka API) per test JVM (mirrors `balance-service`'s /
 * `party-service`'s `PostgresRedpandaTestResource`, CI infra sweep #578 idiom). anacredit-service now
 * boots the `@Incoming("lending-events-in")` consumer and the `loan_stage_projection` Flyway migration
 * (ADR-0037 follow-up, issue #638) — a real broker + DB are needed at boot, not an in-memory switch.
 *
 * Also sets `mp.messaging.incoming.lending-events-in.group.id` here — a genuine runtime property
 * source, not YAML — for the same reason production sets it via env vars (issue #686): SmallRye
 * Config's YAML source unconditionally quotes any leaf map key containing a literal dot, so a
 * `group.id` written as a YAML key in `application.yaml` never resolves. This returned Map does not
 * go through that flattener, so it's the one place in the test tree that can genuinely exercise the
 * fixed group id end-to-end (see [com.openbank.anacredit.integration.LendingEventsConsumerGroupIdBootIT]).
 */
class PostgresRedpandaTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<*>? = null
    private var redpanda: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withUsername("openbank").withPassword("openbank_secret").withDatabaseName("openbank_anacredit_it")
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
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        val bootstrap = rp.bootstrapServers
        lastBootstrapServers = bootstrap
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_anacredit_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_anacredit_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "kafka.bootstrap.servers" to bootstrap,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to bootstrap,
            "mp.messaging.incoming.lending-events-in.group.id" to "anacredit-service-lending",
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

    companion object {
        private const val POSTGRES_IMAGE = "postgres:16.3-alpine"
        private const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"

        /**
         * Set by [start] and read by test classes that need to talk to the broker directly (e.g.
         * [com.openbank.anacredit.integration.LendingEventsConsumerGroupIdBootIT]'s `AdminClient`). A
         * static holder is simplest here since [QuarkusTestResourceLifecycleManager] instances aren't
         * injectable.
         */
        @Volatile
        var lastBootstrapServers: String? = null
            private set
    }
}
