// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.integration

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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of the onboarding read-model projection.
 *
 * onboarding-service is a pure consumer: every row of the `onboarding_records` funnel projection
 * arrives on the `party-events-in` Kafka stream (shared `openbank.party.events` in the deployment)
 * and is persisted before the record is acknowledged. The spans asserted here are the service's
 * OWN — the Kafka connector's OpenTelemetry instrumentation produces the consume span for the real
 * broker record, and Quarkus' JDBC instrumentation produces the span for the real Postgres INSERT;
 * [RecordingSpanExporter] only adds an in-memory `SpanProcessor` so a contract can read what an
 * OTLP collector would receive. Nothing asserted here is created by the test, so every assertion
 * can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually committed the
 * projection row happened *inside* the trace of the Kafka record that carried it. A persist that
 * escaped the consume context — a fire-and-forget executor, a reactive hop that dropped the
 * context — still writes the row and still acks the record, is invisible to every persistence
 * assertion this module already had (ScaEnrolmentProjectionIT drives InMemory channels), and lands
 * in a trace of its own.
 *
 * The InMemory-channel ITs cannot produce this evidence: the InMemory connector carries no Kafka
 * instrumentation, so there is no `<topic> receive` span to attach the INSERT to. A real broker
 * is what makes the consume span exist; a dedicated topic keeps this contract hermetic.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); the PARTY_CREATED projection is the funnel's first write, so it is the right first
 * contract here.
 */
@QuarkusTest
@TestProfile(OnboardingTraceContractIT.OnboardingTraceProfile::class)
class OnboardingTraceContractIT {

    class OnboardingTraceProfile : TraceContractProfile() {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(QuarkusTestProfile.TestResourceEntry(TraceKafkaResource::class.java))

        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            // The deployed channel subscribes via singular `topic`, so the IT overrides `topic` —
            // overriding `topics` would collide with it (SRMSG18022). A dedicated trace topic and
            // group keep the contract hermetic and clear of the deployed consumer groups (the
            // deployment's group.id comes from a msg-override properties file that does not exist
            // in tests, so without this override the group would fall back to the application
            // name).
            "mp.messaging.incoming.party-events-in.topic" to TRACE_TOPIC,
            "mp.messaging.incoming.party-events-in.group.id" to "onboarding-trace-contract-it",
            "mp.messaging.incoming.party-events-in.auto.offset.reset" to "earliest",
        )
    }

    /** Real-broker twin of the InMemory channels used by ScaEnrolmentProjectionIT. */
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
            const val TRACE_TOPIC = "openbank.onboarding.trace.contract.it"
        }
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `projecting a PARTY_CREATED keeps the consumed record and its INSERT in one trace`() {
        val partyId = UUID.randomUUID()

        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to config("kafka.bootstrap.servers"),
                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "acks" to "all",
            ),
        ).use { producer ->
            producer.send(ProducerRecord(TRACE_TOPIC, "trace-$partyId", envelope(partyId)))
                .get(15, TimeUnit.SECONDS)
        }

        awaitRow(partyId)

        exporter.contract()
            .requiresSpan("$TRACE_TOPIC receive")
            .requiresAttribute("$TRACE_TOPIC receive", "messaging.operation")
            .requiresSpan("INSERT onboarding_records")
            .requiresAttribute("INSERT onboarding_records", "db.sql.table")
            .requiresSameTrace("$TRACE_TOPIC receive", "INSERT onboarding_records")
            .hasNoErrorSpan()
            .verifiedAs("onboarding-party-created-projection")
    }

    private fun envelope(partyId: UUID) = """
        {
          "eventType": "PARTY_CREATED",
          "partyId": "$partyId",
          "legalName": "Trace Contract",
          "email": "trace@example.test",
          "occurredAt": "${Instant.now()}"
        }
    """.trimIndent()

    private fun awaitRow(partyId: UUID) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (count(partyId) == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(count(partyId)).describedAs("onboarding_records row for $partyId").isEqualTo(1)
    }

    private fun count(partyId: UUID): Int = jdbc().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM onboarding_records WHERE party_id = ?").use { query ->
            query.setObject(1, partyId)
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
