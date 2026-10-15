// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.audit.it.AgentAuditKafkaTestResource
import com.openbank.audit.it.AgentAuditKafkaTestResource.Companion.AGENT_DLQ_TOPIC
import com.openbank.audit.it.AgentAuditKafkaTestResource.Companion.AGENT_TOPIC
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * D5 blocker #12256, transport half: a published `agent-audit-events-in` record becomes a
 * committed row in `audit_entries` through the REAL broker path, with the AI-attribution
 * fields the producer merges into the envelope (`model_id`, `prompt_hash`, `policy_decision`)
 * round-tripped intact inside the chain-hashed `payload` JSON.
 *
 * **What this adds over [AgentAuditEventPersistenceIT].** That IT drives the CDI consumer
 * directly with a hand-built `Message` — it proves the parse-and-persist code path and the
 * ack-after-persist ordering inside [com.openbank.audit.application.AgentAuditConsumer], but
 * it never touches Kafka, so it cannot see the channel being DISABLED, mis-grouped, or
 * DLQ-miswired in the deployed topology. This IT is the gap the fleet has been burned by
 * (#3271, #686, #5745): it runs the exact deployed configuration — enabled channel,
 * `group.id=audit-service-agent-provenance` (the group the KafkaUser ACL grants), nested
 * `failure-strategy: dead-letter-queue` with an explicit per-channel topic — against a real
 * Redpanda broker and Testcontainers Postgres, publishes what `DurableAgentAuditPublisher`
 * actually puts on the wire, and reads the row back over a plain JDBC connection that shares
 * no code with the write path.
 *
 * **Persistence-before-ack, stated plainly.** The strict ordering (ack only after the
 * append-only commit) is a property of `AgentAuditConsumer.consume` — and the failure side
 * is an explicit `message.nack` in a catch block, not a rethrow: this method infers MANUAL
 * acknowledgment, under which a rethrown exception bypasses the nack and cancels the stream
 * instead of parking the record (the pre-#12256 behavior this IT pins red). What the broker
 * adds and this IT asserts: a record whose persist FAILS is nacked and parked in the
 * channel's DLQ topic while a subsequent good record still persists — if the channel wedged
 * on the DLQ send or acked before persisting, the good record would starve behind the
 * poison and the assertion below would time out.
 *
 * **The transport flag is the point.** Unlike the unit IT, this one does not inherit
 * `enabled=false` from application.yaml: `AgentAuditKafkaTestResource` enables the channel
 * exactly as the gitops Deployment does (`AUDIT_AGENT_AUDIT_KAFKA_ENABLED=true`), so a
 * regression that flips the channel back off fails here as a timeout, not a green skip.
 */
@QuarkusTest
@TestProfile(AgentAuditKafkaPersistenceIT.RealKafkaProfile::class)
class AgentAuditKafkaPersistenceIT {
    class RealKafkaProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(AgentAuditKafkaTestResource::class.java))
    }

    @Inject
    lateinit var objectMapper: ObjectMapper

    /**
     * The wire shape `DurableAgentAuditPublisher.publish` produces: the audited operation's
     * free-form payload (read off `AgentRunAuditor` / `AgentPolicyGate` — `model_id`,
     * `prompt_hash`, `policy_decision`, `tool_calls`) merged UNDER the fixed
     * `AgentAuditEventEnvelope` fields (`Map +` semantics — the envelope wins a key
     * collision). Key spellings are the producer's, not invented here; rename a field on
     * either side and this goes red.
     */
    private fun agentAttributionEnvelope(eventId: UUID, actorId: String) = """
        {
          "plane": "customer",
          "trigger": "chat",
          "model_id": "groq/llama-3.3-70b-versatile",
          "prompt_hash": "sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
          "policy_decision": "DENY",
          "tool_calls": [{"tool": "get_account", "allowed": false}],
          "total_tokens": 482,
          "eventId": "$eventId",
          "eventType": "agent.mcp.tool_call",
          "aggregateType": "mcp.tool",
          "aggregateId": "acc-42",
          "actorId": "$actorId",
          "actorType": "AI_AGENT",
          "sourceService": "agent-service",
          "correlationId": "trace-$eventId",
          "occurredAt": "2026-08-21T12:34:56Z",
          "channel": "mcp",
          "actChain": [],
          "sessionId": "sess-$eventId",
          "result": "DENIED"
        }
    """.trimIndent()

    @Test
    fun `a published agent attribution event persists with AI fields intact`() {
        val eventId = UUID.randomUUID()
        val actorId = "agent:kafka-${UUID.randomUUID()}"

        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to config("kafka.bootstrap.servers"),
                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "acks" to "all",
            ),
        ).use { producer ->
            producer.send(ProducerRecord(AGENT_TOPIC, actorId, agentAttributionEnvelope(eventId, actorId)))
                .get(15, TimeUnit.SECONDS)
        }

        awaitRow(eventId)

        jdbc().use { c ->
            c.prepareStatement(
                """
                select actor_type, source_service, aggregate_id, payload
                  from audit_entries where entry_id = ?
                """.trimIndent(),
            ).use { st ->
                st.setObject(1, eventId)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).describedAs("a row for the published agent event must exist").isTrue()
                    assertThat(rs.getString("actor_type")).isEqualTo("AI_AGENT")
                    assertThat(rs.getString("source_service")).isEqualTo("agent-service")
                    assertThat(rs.getString("aggregate_id")).isEqualTo("acc-42")
                    val payload: JsonNode = objectMapper.readTree(rs.getString("payload"))
                    // The D5 attribution fields, verbatim from the producer's payload merge —
                    // these are what a reviewer subpoenas. A JSON-quoting DLQ serializer or a
                    // payload-truncating persist breaks exactly here.
                    assertThat(payload.get("model_id").asText()).isEqualTo("groq/llama-3.3-70b-versatile")
                    assertThat(payload.get("prompt_hash").asText())
                        .isEqualTo("sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
                    assertThat(payload.get("policy_decision").asText()).isEqualTo("DENY")
                    assertThat(payload.get("tool_calls").get(0).get("tool").asText()).isEqualTo("get_account")
                    // The envelope half of the merge survived alongside them.
                    assertThat(payload.get("eventType").asText()).isEqualTo("agent.mcp.tool_call")
                    assertThat(payload.get("result").asText()).isEqualTo("DENIED")
                }
            }
        }
    }

    @Test
    @Suppress("NestedBlockDepth") // Keep broker/SQL resources and fault cleanup paired in this integration scenario.
    fun `a failed persist is parked in the channel DLQ and does not starve later records`() {
        val rejected = UUID.randomUUID()
        val accepted = UUID.randomUUID()
        val poison = "not-json-${UUID.randomUUID()}"
        val rejectedPayload = agentAttributionEnvelope(rejected, "agent:rejected-${UUID.randomUUID()}")
        val constraint = "reject_agent_${rejected.toString().replace("-", "")}"
        sql(
            "ALTER TABLE audit_entries ADD CONSTRAINT $constraint CHECK (actor_id NOT LIKE 'agent:rejected-%') NOT VALID",
        )

        KafkaConsumer<String, String>(
            mapOf(
                "bootstrap.servers" to config("kafka.bootstrap.servers"),
                "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                "group.id" to "agent-dlq-probe-${UUID.randomUUID()}",
                "auto.offset.reset" to "earliest",
                "enable.auto.commit" to "false",
            ),
        ).use { dlq ->
            dlq.subscribe(listOf(AGENT_DLQ_TOPIC))
            KafkaProducer<String, String>(
                mapOf(
                    "bootstrap.servers" to config("kafka.bootstrap.servers"),
                    "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                    "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                    "acks" to "all",
                ),
            ).use { producer ->
                try {
                    listOf(
                        poison,
                        rejectedPayload,
                        agentAttributionEnvelope(accepted, "agent:accepted-${UUID.randomUUID()}"),
                    ).forEach {
                        producer.send(ProducerRecord(AGENT_TOPIC, "agent-probe", it)).get(15, TimeUnit.SECONDS)
                    }
                    val failed = mutableListOf<String>()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    while (failed.size < 2 && System.nanoTime() < deadline) {
                        dlq.poll(Duration.ofMillis(200)).forEach { failed += it.value() }
                    }
                    // The persist-before-ack ordering, observable from the broker side: a record
                    // whose persist failed was NACKED and parked in the channel DLQ — it never
                    // carried a committed offset past itself.
                    assertThat(failed).containsExactlyInAnyOrder(poison, rejectedPayload)
                    // And the channel is not wedged behind the failure: the good record persisted.
                    awaitRow(accepted)
                } finally {
                    sql("ALTER TABLE audit_entries DROP CONSTRAINT $constraint")
                }
                // Replay after repairing the store failure preserves the producer's identity
                // (at-least-once idempotency on the producer event id).
                producer.send(ProducerRecord(AGENT_TOPIC, "agent-probe", rejectedPayload)).get(15, TimeUnit.SECONDS)
                awaitRow(rejected)
            }
        }
    }

    private fun awaitRow(eventId: UUID) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (count(eventId) == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(count(eventId)).describedAs("audit_entries row for $eventId").isEqualTo(1)
    }

    private fun count(eventId: UUID): Int = jdbc().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM audit_entries WHERE entry_id = ?").use { query ->
            query.setObject(1, eventId)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun config(name: String): String = ConfigProvider.getConfig().getValue(name, String::class.java)

    private fun jdbc() = DriverManager.getConnection(
        config("quarkus.datasource.jdbc.url"),
        config("quarkus.datasource.username"),
        config("quarkus.datasource.password"),
    )

    private fun sql(statement: String) {
        jdbc().use { connection -> connection.createStatement().use { it.execute(statement) } }
    }
}
