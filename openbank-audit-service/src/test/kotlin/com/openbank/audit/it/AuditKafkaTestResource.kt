// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit

class AuditKafkaTestResource : QuarkusTestResourceLifecycleManager {
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
                admin.createTopics(listOf(NewTopic(INPUT_TOPIC, 1, 1), NewTopic(DLQ_TOPIC, 1, 1)))
                    .all().get(20, TimeUnit.SECONDS)
            }
            return settings + mapOf(
                "kafka.bootstrap.servers" to kafka.bootstrapServers,
                "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to kafka.bootstrapServers,
                "mp.messaging.incoming.audit-events-in.connector" to "smallrye-kafka",
                "mp.messaging.incoming.audit-events-in.topics" to INPUT_TOPIC,
                "mp.messaging.incoming.audit-events-in.group.id" to "audit-ingestion-test",
                "mp.messaging.incoming.audit-events-in.auto.offset.reset" to "earliest",
                "mp.messaging.incoming.agent-audit-events-in.enabled" to "false",
            )
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    override fun stop() {
        try {
            broker?.let {
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
        const val INPUT_TOPIC = "openbank.audit.ingestion-test"
        const val DLQ_TOPIC = "openbank.dlq.audit.audit-events-in"
    }
}
