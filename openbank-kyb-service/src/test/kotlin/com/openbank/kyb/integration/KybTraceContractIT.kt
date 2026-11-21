// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.kyb.infrastructure.kafka.MonitoredSubjectMatchedConsumer
import com.openbank.kyb.infrastructure.kafka.MonitoredSubjectMatchedWire
import com.openbank.kyb.it.KybMonitoringJourney
import com.openbank.kyb.it.StubDocumentGateway
import com.openbank.kyb.it.StubMonitoredSubjectRegistry
import com.openbank.kyb.it.StubPartyGateway
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.TopicExistsException
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of recording a monitoring finding on a business case.
 *
 * A `MONITORED_SUBJECT_MATCHED` record on the `sanctions-monitoring-in` stream is the write-side
 * of KYB ongoing monitoring (ADR-0376 D6): the finding is persisted with the case row, atomically
 * under the case's optimistic lock (see [MonitoringFindingService] — a redelivery writes nothing,
 * two racing deliveries collide and re-read). The spans asserted here are the service's OWN —
 * the Kafka connector's OpenTelemetry instrumentation produces the consume span for the real
 * broker record, and Quarkus' JDBC instrumentation produces the span for the real Postgres
 * UPDATE; `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a contract can read
 * what an OTLP collector would receive. Nothing asserted here is created by the test, so every
 * assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the UPDATE that actually recorded the
 * finding happened *inside* the trace of the Kafka record that carried it. A finding write that
 * escaped the consume context still updates the case and still acks the record, is invisible to
 * every assertion this module already had, and lands in a trace of its own — and a compliance
 * finding no investigator can tie back to the delivery that raised it is a gap in the AML
 * evidence trail exactly where the trail matters most.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); the monitoring finding is KYB's highest-stakes write, so it is the right first contract
 * here.
 */
@QuarkusTest
@TestProfile(KybTraceContractIT.KybTraceProfile::class)
class KybTraceContractIT {

    class KybTraceProfile : TraceContractProfile() {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(
                com.openbank.libs.testing.containers.PostgresRedpandaTestResource::class.java,
            ),
        )

        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "openbank.kyb.monitoring.enabled" to "true",
            "openbank.kyb.monitoring.relay.every" to "1s",
            "openbank.kyb.monitoring.relay.initial-delay" to "1s",
            "mp.messaging.incoming.sanctions-monitoring-in.auto.commit.interval.ms" to "10000",
            "mp.messaging.incoming.sanctions-monitoring-in.topic" to TRACE_TOPIC,
            "mp.messaging.incoming.sanctions-monitoring-in.group.id" to "kyb-trace-contract-it",
            "mp.messaging.incoming.party-events-in.group.id" to "kyb-trace-contract-it-party",
            // Pin the sampler so the contract is hermetic: a parent-based sampler makes it a
            // function of incoming record headers' sampled flags the test does not control, and
            // an unsampled parent legitimately yields no spans. Sampling policy is the
            // deployment's concern, not the contract's.
            "quarkus.otel.traces.sampler" to "always_on",
        )
    }

    @Inject lateinit var parties: StubPartyGateway

    @Inject lateinit var documents: StubDocumentGateway

    @Inject lateinit var registry: StubMonitoredSubjectRegistry

    @Inject lateinit var exporter: RecordingSpanExporter

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var jdbcUser: String

    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var jdbcPassword: String

    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var bootstrap: String

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API", "ROLE_KYC"])
    fun `recording a monitoring finding keeps the consumed record and the case UPDATE in one trace`() {
        val journey = KybMonitoringJourney(parties, documents, jdbcUrl, jdbcUser, jdbcPassword)
        ensureTopics()
        val caseId = journey.approved("71000127")

        // Nothing here calls the relay: the scheduler does — the same arrangement
        // KybMonitoringWireShapeIT proves the deployed topology with.
        await("the schedule to register the monitored subject") {
            journey.rows(caseId).isNotEmpty() && journey.rows(caseId).all { it.syncState == "SYNCED" }
        }
        val subjectRef = registry.registered.single { it.name == "Emil Plánovaný" }.subjectRef

        val findingId = UUID.randomUUID()
        producer().use { p ->
            p.send(findingRecord(findingId, subjectRef)).get(SEND_SECONDS, TimeUnit.SECONDS)
        }

        await("the finding to be waiting for an operator") { journey.stored(caseId).reviewOpen }

        exporter.contract()
            .requiresSpan("$TRACE_TOPIC receive")
            .requiresAttribute("$TRACE_TOPIC receive", "messaging.operation")
            .requiresSpan("UPDATE kyb_cases")
            .requiresAttribute("UPDATE kyb_cases", "db.sql.table")
            // The fixture's own REST journey produces sibling UPDATE kyb_cases spans in its
            // request traces, so a plain requiresSameTrace would measure the whole recorder.
            // The dedicated TRACE_TOPIC makes the consume span unambiguous — anchor on it.
            .requiresSameTraceAs("$TRACE_TOPIC receive", "UPDATE kyb_cases")
            .hasNoErrorSpan()
            .verifiedAs("kyb-monitoring-finding-record")
    }

    private fun findingRecord(findingId: UUID, subjectRef: String) = ProducerRecord(
        TRACE_TOPIC,
        "trace-$findingId",
        MonitoredSubjectMatchedWire.json(
            MonitoredSubjectMatchedWire.body(subjectRef, findingId),
        ),
    ).also { record ->
        // The event type is the ce-type Kafka HEADER, never a body field — a bare JSON record
        // is acked as malformed (MonitoredSubjectMatchedConsumer).
        record.headers().add("ce-type", MonitoredSubjectMatchedConsumer.EVENT_MATCHED.toByteArray())
    }

    private fun producer() = KafkaProducer<String, String>(
        mapOf(
            "bootstrap.servers" to bootstrap,
            "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "acks" to "all",
        ),
    )

    private fun ensureTopics() {
        Admin.create(mapOf<String, Any>("bootstrap.servers" to bootstrap)).use { admin ->
            try {
                admin.createTopics(listOf(NewTopic(TRACE_TOPIC, 1, 1)))
                    .all().get(SEND_SECONDS, TimeUnit.SECONDS)
            } catch (alreadyExists: ExecutionException) {
                // The connector may already have auto-created the topic for its own channel;
                // existence is all this contract needs.
                if (alreadyExists.cause !is TopicExistsException) throw alreadyExists
            }
        }
    }

    private fun await(describedAs: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(50)
        assertThat(condition()).describedAs(describedAs).isTrue()
    }

    companion object {
        private const val SEND_SECONDS = 15L
        private const val AWAIT_SECONDS = 60L

        // Dedicated topic for this contract: the deployed channel shares
        // openbank.sanctions.screening.event with the rest of the platform, whose sibling
        // records would deposit same-named spans in other traces and defeat the same-trace claim.
        private const val TRACE_TOPIC = "openbank.kyb.trace.contract.it"
    }
}
