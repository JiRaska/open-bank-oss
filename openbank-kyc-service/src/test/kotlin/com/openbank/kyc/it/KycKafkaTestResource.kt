// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.it

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit

open class KycKafkaTestResource : QuarkusTestResourceLifecycleManager {
    private val postgres = PostgresTestResource()
    private var broker: RedpandaContainer? = null

    private var started = false

    override fun start(): Map<String, String> {
        val settings = postgres.start()
        try {
            val kafka = RedpandaContainer(
                DockerImageName.parse("redpandadata/redpanda:v24.1.2")
                    .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
            )
            broker = kafka
            kafka.start()
            started = true
            TestInfrastructureEvidence.record("redpanda", kafka.dockerImageName, "started")
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                admin.createTopics(
                    listOf(
                        TOPIC,
                        "openbank.kyc.events",
                        "openbank.dlq.kyc.party-events-in",
                        SANCTIONS_TOPIC,
                        "openbank.dlq.kyc.sanctions-list-changes",
                    ).map { NewTopic(it, 1, 1) },
                )
                    .all().get(20, TimeUnit.SECONDS)
            }
            return settings + mapOf(
                "kafka.bootstrap.servers" to kafka.bootstrapServers,
                "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to kafka.bootstrapServers,
                "mp.messaging.incoming.party-events-in.bootstrap.servers" to kafka.bootstrapServers,
                "mp.messaging.outgoing.kyc-outbox-out.bootstrap.servers" to kafka.bootstrapServers,
                "mp.messaging.incoming.party-events-in.group.id" to GROUP,
                "mp.messaging.incoming.party-events-in.auto.offset.reset" to "earliest",
                "mp.messaging.incoming.party-events-in.auto.commit.interval.ms" to "100",
                "openbank.outbox.dispatch-enabled" to "false",
                "openbank.kyc.auto-approve" to "false",
                "quarkus.scheduler.enabled" to "false",
                "quarkus.rest-client.sanctions-service.url" to "http://127.0.0.1:1",
                "authz.enforce" to "false",
            ) + overrides()
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    /** Settings a subclass layers over the defaults (applied last, so they win). */
    protected open fun overrides(): Map<String, String> = emptyMap()

    override fun stop() {
        try {
            broker?.let {
                it.stop()
                if (started) TestInfrastructureEvidence.record("redpanda", it.dockerImageName, "stopped")
            }
            started = false
            broker = null
        } finally {
            postgres.stop()
        }
    }

    companion object {
        const val TOPIC = "openbank.party.events"
        const val GROUP = "kyc-business-source-it"
        const val SANCTIONS_TOPIC = "openbank.sanctions.screening.event"
    }
}
