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

/**
 * Real-broker twin of [AuditKafkaTestResource] for the D5 `agent-audit-events-in` channel
 * (#12256). That resource force-DISABLES the agent channel because its `AuditDlqIT` drives
 * only the legacy `audit-events-in` stream; this one enables the agent channel against
 * Redpanda so `AgentAuditKafkaPersistenceIT` can prove the deployed topology (enabled
 * channel, explicit group.id, dead-letter-queue strategy with an explicit per-channel topic)
 * end to end. The channels are split across two brokers-in-test because enabling both in one
 * JVM would couple two unrelated streams' redelivery timing.
 */
class AgentAuditKafkaTestResource : QuarkusTestResourceLifecycleManager {
    private val postgres = PostgresTestResource()
    private var broker: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        val settings = postgres.start()
        try {
            val kafka = RedpandaContainer(
                DockerImageName.parse(REDPANDA_IMAGE)
                    .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
            )
            broker = kafka
            kafka.start()
            TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "started")
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                admin.createTopics(listOf(NewTopic(AGENT_TOPIC, 1, 1), NewTopic(AGENT_DLQ_TOPIC, 1, 1)))
                    .all().get(20, TimeUnit.SECONDS)
            }
            return settings + mapOf(
                "kafka.bootstrap.servers" to kafka.bootstrapServers,
                "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to kafka.bootstrapServers,
                // The deployment decision this module tests: the channel is ENABLED, with the
                // group.id the KafkaUser ACL grants (audit-service-agent-provenance) — as plain
                // properties (never dotted YAML keys, #686). failure-strategy:
                // dead-letter-queue and its topic are inherited from application.yaml exactly as
                // deployed; AuditDlqIT owns the legacy channel's DLQ path, this module's
                // AgentAuditKafkaPersistenceIT proves it holds for THIS channel.
                "mp.messaging.incoming.agent-audit-events-in.enabled" to "true",
                "mp.messaging.incoming.agent-audit-events-in.topic" to AGENT_TOPIC,
                "mp.messaging.incoming.agent-audit-events-in.group.id" to "audit-service-agent-provenance-it",
                "mp.messaging.incoming.agent-audit-events-in.auto.offset.reset" to "earliest",
                // The legacy stream stays off in this JVM — its own IT covers it.
                "mp.messaging.incoming.audit-events-in.enabled" to "false",
            )
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    override fun stop() {
        broker?.let {
            it.stop()
            TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "stopped")
        }
        broker = null
        postgres.stop()
    }

    companion object {
        const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
        const val AGENT_TOPIC = "openbank.agent.audit.events.it"

        // The deployed DLQ topic name, deliberately: this IT proves the application.yaml
        // wiring, not a test-only twin.
        const val AGENT_DLQ_TOPIC = "openbank.dlq.audit.agent-audit-events-in"
    }
}
