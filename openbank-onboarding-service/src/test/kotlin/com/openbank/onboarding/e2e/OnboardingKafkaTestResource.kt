// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.e2e

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit

/**
 * Isolated PostgreSQL + Redpanda for the onboarding E2E suite. The service projects four
 * upstream topics (party / kyc / sca / kyb) and serves the result over HTTP, so the suite needs
 * a real broker at both ends. Topics are created explicitly (no reliance on broker-side
 * auto-create), each incoming channel gets a dedicated group so the committed-offset waits in
 * the tests are per-channel, and Flyway runs the service's own migrations against the container
 * database, exactly like `OnboardingPostgresTestResource` does for the repository ITs.
 */
class OnboardingKafkaTestResource : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var redpanda: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName(DB)
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

        val bootstrap = rp.bootstrapServers
        liveBootstrap = bootstrap
        Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
            admin.createTopics(
                listOf(PARTY_TOPIC, KYC_TOPIC, SCA_TOPIC, KYB_TOPIC).map { NewTopic(it, 1, 1) },
            ).all().get(20, TimeUnit.SECONDS)
        }

        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/$DB",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/$DB",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.devservices.enabled" to "false",
            "kafka.bootstrap.servers" to bootstrap,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to bootstrap,
        ) + incomingChannel("party-events-in", PARTY_GROUP, bootstrap) +
            incomingChannel("kyc-events-in", KYC_GROUP, bootstrap) +
            incomingChannel("sca-events-in", SCA_GROUP, bootstrap) +
            incomingChannel("kyb-events-in", KYB_GROUP, bootstrap)
    }

    // group.id and auto.offset.reset cannot live in application.yaml: SmallRye's YAML source
    // quotes the dotted leaf key and the connector never reads it (#686) — so they are set here
    // from a real config source, per channel, the same fix the fleet uses via msg-override
    // ConfigMaps in production.
    private fun incomingChannel(channel: String, group: String, bootstrap: String): Map<String, String> = mapOf(
        "mp.messaging.incoming.$channel.bootstrap.servers" to bootstrap,
        "mp.messaging.incoming.$channel.group.id" to group,
        "mp.messaging.incoming.$channel.auto.offset.reset" to "earliest",
    )

    override fun stop() {
        liveBootstrap = null
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
        /**
         * The live broker address of the started container. The producer reads this instead of
         * `ConfigProvider`: after a `@TestProfile` reboot under parallel-module load the
         * application-side config can briefly disagree with the actual container, and publishing
         * against a stale bootstrap fails with `BrokerNotAvailableException` while the broker is
         * plainly alive. Null while the resource is not running.
         */
        @Volatile
        var liveBootstrap: String? = null

        const val DB = "openbank_onboarding_it"
        const val PARTY_TOPIC = "openbank.party.events"
        const val KYC_TOPIC = "openbank.kyc.events"
        const val SCA_TOPIC = "openbank.sca.events"
        const val KYB_TOPIC = "openbank.kyb.events"

        const val PARTY_GROUP = "onboarding-e2e-party"
        const val KYC_GROUP = "onboarding-e2e-kyc"
        const val SCA_GROUP = "onboarding-e2e-sca"
        const val KYB_GROUP = "onboarding-e2e-kyb"

        private const val POSTGRES_IMAGE = "postgres:18.6-alpine"
        private const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
    }
}
