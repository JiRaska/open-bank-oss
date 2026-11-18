// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of the audit-trail append.
 *
 * audit-service is a pure consumer: every row of the append-only, chain-hashed `audit_entries`
 * trail (ADR-0023) arrives on the `audit-events-in` Kafka stream and is persisted before the
 * record is acknowledged. The spans asserted here are the service's OWN — the Kafka connector's
 * OpenTelemetry instrumentation produces the consume span for the real broker record, and
 * Quarkus' JDBC instrumentation produces the span for the real Postgres INSERT;
 * [TraceContractRecorder] only adds an in-memory `SpanProcessor` so a contract can read what an
 * OTLP collector would receive. Nothing asserted here is created by the test, so every assertion
 * can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually committed the
 * audit row happened *inside* the trace of the Kafka record that carried it. A persist that
 * escaped the consume context — a fire-and-forget executor, a reactive hop that dropped the
 * context — still writes the row and still acks the record, is invisible to every persistence
 * assertion this module already had, and lands in a trace of its own. For audit that is worse
 * than for any other service: the row IS the evidentiary record, and a row no operator can tie
 * back to the delivery that produced it is a gap in the tamper-evident trail itself.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); the generic `audit-events-in` append is the service's only write path, so it is the
 * right first contract here.
 */
@QuarkusTest
@TestProfile(AuditTraceContractIT.AuditTraceProfile::class)
class AuditTraceContractIT {

    class AuditTraceProfile : TraceContractProfile() {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(QuarkusTestProfile.TestResourceEntry(TraceKafkaResource::class.java))

        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            // The deployment decision under test: the legacy stream ENABLED against a real broker,
            // with the group and reset the deployed topology carries. The deployed channel
            // subscribes via `topics` (a comma-separated list), so the IT overrides `topics` —
            // overriding `topic` would collide with it (SRMSG18022). The D5 streams stay off —
            // they own their own transport ITs (AgentAuditKafkaPersistenceIT and friends).
            "mp.messaging.incoming.audit-events-in.enabled" to "true",
            "mp.messaging.incoming.audit-events-in.topics" to TRACE_TOPIC,
            "mp.messaging.incoming.audit-events-in.group.id" to "audit-trace-contract-it",
            "mp.messaging.incoming.audit-events-in.auto.offset.reset" to "earliest",
            "mp.messaging.incoming.agent-audit-events-in.enabled" to "false",
            "mp.messaging.incoming.context-audit-commitments-in.enabled" to "false",
        )
    }

    /** Real-broker twin of [com.openbank.audit.it.AuditKafkaTestResource] for this contract. */
    class TraceKafkaResource : QuarkusTestResourceLifecycleManager {
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
                    admin.createTopics(listOf(NewTopic(TRACE_TOPIC, 1, 1)))
                        .all().get(20, TimeUnit.SECONDS)
                }
                return settings + mapOf(
                    "kafka.bootstrap.servers" to kafka.bootstrapServers,
                    "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to kafka.bootstrapServers,
                    "quarkus.kafka.devservices.enabled" to "false",
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
            const val TRACE_TOPIC = "openbank.audit.trace.contract.it"
        }
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `appending an audit entry keeps the consumed record and its INSERT in one trace`() {
        val eventId = UUID.randomUUID()

        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to config("kafka.bootstrap.servers"),
                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "acks" to "all",
            ),
        ).use { producer ->
            producer.send(ProducerRecord(TraceKafkaResource.TRACE_TOPIC, "trace-$eventId", envelope(eventId)))
                .get(15, TimeUnit.SECONDS)
        }

        awaitRow(eventId)

        exporter.contract()
            .requiresSpan("$TRACE_TOPIC receive")
            .requiresAttribute("$TRACE_TOPIC receive", "messaging.operation")
            .requiresSpan("INSERT audit_entries")
            .requiresAttribute("INSERT audit_entries", "db.sql.table")
            .requiresSameTrace("$TRACE_TOPIC receive", "INSERT audit_entries")
            .hasNoErrorSpan()
            .verifiedAs("audit-entry-append")
    }

    private fun envelope(eventId: UUID) = """
        {
          "eventId": "$eventId",
          "eventType": "account.opened",
          "aggregateType": "ACCOUNT",
          "aggregateId": "acc-trace-${UUID.randomUUID()}",
          "actorId": "operator:trace-${UUID.randomUUID()}",
          "actorType": "HUMAN",
          "sourceService": "openbank-account-service",
          "correlationId": "trace-$eventId",
          "occurredAt": "2026-11-18T07:30:00Z"
        }
    """.trimIndent()

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

    companion object {
        private const val TRACE_TOPIC = TraceKafkaResource.TRACE_TOPIC
    }
}
