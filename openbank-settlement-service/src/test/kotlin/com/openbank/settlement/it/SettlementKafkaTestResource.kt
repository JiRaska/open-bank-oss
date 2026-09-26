// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.jboss.logging.Logger
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class SettlementKafkaTestResource : QuarkusTestResourceLifecycleManager {
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
            TestInfrastructureEvidence.record("kafka", kafka.dockerImageName, "started")
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                admin.createTopics(listOf(NewTopic(TOPIC, 1, 1)))
                    .all().get(20, TimeUnit.SECONDS)
            }
            return settings + mapOf(
                "kafka.bootstrap.servers" to kafka.bootstrapServers,
                "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to kafka.bootstrapServers,
                "mp.messaging.outgoing.settlement-events-out.connector" to "smallrye-kafka",
                "openbank.outbox.dispatch-enabled" to "true",
                "openbank.outbox.poll-interval" to "1s",
                "openbank.outbox.initial-delay" to "1s",
            )
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    override fun stop() {
        try {
            broker?.let {
                // Retain broker-side evidence before Testcontainers removes a failed fixture.
                runCatching {
                    val target = Path.of("build", "test-results", "testcontainers", "settlement-kafka.log")
                    Files.createDirectories(target.parent)
                    Files.writeString(target, it.logs)
                }.onFailure { failure ->
                    Logger.getLogger(SettlementKafkaTestResource::class.java)
                        .warn("Could not retain test broker diagnostics", failure)
                }
                it.stop()
                if (started) TestInfrastructureEvidence.record("kafka", it.dockerImageName, "stopped")
            }
            started = false
            broker = null
        } finally {
            postgres.stop()
        }
    }

    companion object {
        const val TOPIC = "openbank.settlement.events"
    }
}
