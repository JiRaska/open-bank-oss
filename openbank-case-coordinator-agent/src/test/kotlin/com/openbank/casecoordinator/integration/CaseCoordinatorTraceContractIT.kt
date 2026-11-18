// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.integration

import com.openbank.casecoordinator.PostgresTestResource
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of the agent kill-switch projection.
 *
 * case-coordinator-agent is an event-driven consumer: a `agent.killswitch.set` record on the
 * `agent-kill-switch-events-in` stream is projected into the `case_kill_switch` state table before
 * the record is acknowledged, and shadow-pilot incident cases are halted inside the same delivery.
 * The spans asserted here are the service's OWN — the Kafka connector's OpenTelemetry
 * instrumentation produces the consume span for the real broker record, and Quarkus' JDBC
 * instrumentation produces the span for the real Postgres INSERT; [RecordingSpanExporter] only
 * adds an in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually committed the
 * kill-switch row happened *inside* the trace of the Kafka record that carried it. A persist that
 * escaped the consume context — a fire-and-forget executor, a reactive hop that dropped the
 * context — still writes the row and still acks the record, is invisible to every persistence
 * assertion this module already had (CaseKillSwitchStateIT drives the adapter directly), and
 * lands in a trace of its own. For a kill switch that is the difference between an evidentiary
 * containment action and an un attributable state change.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); the generic kill-switch projection is the service's only cross-process write path, so it
 * is the right first contract here.
 */
@QuarkusTest
@TestProfile(CaseCoordinatorTraceContractIT.CaseCoordinatorTraceProfile::class)
class CaseCoordinatorTraceContractIT {

    class CaseCoordinatorTraceProfile : TraceContractProfile() {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(QuarkusTestProfile.TestResourceEntry(TraceKafkaResource::class.java))

        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            // The deployment topic comes from AGENT_KILL_SWITCH_TOPIC; a dedicated trace topic and
            // group keep the contract hermetic and clear of the deployed consumer group.
            "mp.messaging.incoming.agent-kill-switch-events-in.topic" to TRACE_TOPIC,
            "mp.messaging.incoming.agent-kill-switch-events-in.group.id" to "case-coordinator-trace-contract-it",
            "mp.messaging.incoming.agent-kill-switch-events-in.auto.offset.reset" to "earliest",
            // Pin the sampler: with a parent-based sampler the contract becomes a function of
            // the incoming record headers' sampled flag (not controlled here — the plain
            // test producer sets no traceparent), and an unsampled parent legitimately yields
            // no spans (observed as flaky 'observed span names only: []'). The contract asserts
            // span presence and context flow, not sampling policy.
            "quarkus.otel.traces.sampler" to "always_on",
            // The projection adapter is plain Agroal JDBC (no Hibernate ORM), which only emits
            // spans when telemetry is switched on.
            "quarkus.datasource.jdbc.telemetry" to "true",
        )
    }

    /** Real-broker twin of the deployed `agent-kill-switch-events-in` channel for this contract. */
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
            const val TRACE_TOPIC = "openbank.casecoordinator.trace.contract.it"
        }
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `applying an agent killswitch set keeps the consumed record and its INSERT in one trace`() {
        val eventId = UUID.randomUUID()
        val scope = "rca-investigator"

        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to config("kafka.bootstrap.servers"),
                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "acks" to "all",
            ),
        ).use { producer ->
            producer.send(ProducerRecord(TRACE_TOPIC, "trace-$eventId", envelope(eventId, scope)))
                .get(15, TimeUnit.SECONDS)
        }

        awaitRow(eventId)

        exporter.contract()
            .requiresSpan("$TRACE_TOPIC receive")
            .requiresAttribute("$TRACE_TOPIC receive", "messaging.operation")
            // Plain-JDBC adapter: Agroal only emits spans with jdbc.telemetry on (profile above),
            // and names them `<operation> <schema>.<table>` — is the service default URL's db
            // is `openbank_it`.
            .requiresSpan("INSERT openbank_casecoordinator.case_kill_switch")
            .requiresSameTrace("$TRACE_TOPIC receive", "INSERT openbank_casecoordinator.case_kill_switch")
            .hasNoErrorSpan()
            .verifiedAs("casecoordinator-killswitch-set-projection")
    }

    private fun envelope(eventId: UUID, scope: String) = """
        {
          "eventType": "agent.killswitch.set",
          "aggregateId": "$scope",
          "eventId": "$eventId",
          "reason": "trace contract containment",
          "actorId": "admin:trace-${UUID.randomUUID()}",
          "occurredAt": "${Instant.now()}"
        }
    """.trimIndent()

    private fun awaitRow(eventId: UUID) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (count(eventId) == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(count(eventId)).describedAs("case_kill_switch row for $eventId").isEqualTo(1)
    }

    private fun count(eventId: UUID): Int = jdbc().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM case_kill_switch WHERE source_event_id = ?").use { query ->
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
