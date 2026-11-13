// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.kyb.infrastructure.MicrometerKybMonitoringMetrics
import com.openbank.kyb.infrastructure.kafka.MonitoredSubjectMatchedWire
import com.openbank.kyb.it.KybMonitoringJourney
import com.openbank.kyb.it.StubDocumentGateway
import com.openbank.kyb.it.StubMonitoredSubjectRegistry
import com.openbank.kyb.it.StubPartyGateway
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Ongoing monitoring with nothing called by hand: the REAL schedule sends the registrations, and
 * the finding arrives from a REAL broker in the producer's wire shape — the event type only on the
 * `ce-type` header, a body with no `eventType` ([MonitoredSubjectMatchedWire]).
 *
 * Two defects only this arrangement can see, and both have happened in this fleet:
 *
 *  - a `@Scheduled` method that is not `suspend` carries no Vert.x context, so its first reactive
 *    call aborts the run having done nothing. A test that calls the method supplies the context
 *    itself and passes against the broken code; here the scheduler calls it.
 *  - a consumer that reads the type from the body matches no branch and acks every real record,
 *    while a unit test that hand-writes the body it expects stays green. Here the record comes
 *    through the Kafka connector with real headers, and the observable is the stored case.
 *
 * The profile's values are literals: a test profile loads in a different classloader from the
 * test class, so anything computed in a companion object would be computed twice.
 */
@QuarkusTest
@TestProfile(KybMonitoringWireShapeIT.Profile::class)
class KybMonitoringWireShapeIT {

    class Profile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(PostgresRedpandaTestResource::class.java))

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.kyb.monitoring.enabled" to "true",
            "openbank.kyb.monitoring.relay.every" to "1s",
            "openbank.kyb.monitoring.relay.initial-delay" to "1s",
            "mp.messaging.incoming.sanctions-monitoring-in.group.id" to "kyb-monitoring-wire-it",
            "mp.messaging.incoming.sanctions-monitoring-in.auto.offset.reset" to "earliest",
            "mp.messaging.incoming.sanctions-monitoring-in.auto.commit.interval.ms" to "100",
            "mp.messaging.incoming.party-events-in.group.id" to "kyb-monitoring-wire-it-party",
        )
    }

    @Inject lateinit var parties: StubPartyGateway

    @Inject lateinit var documents: StubDocumentGateway

    @Inject lateinit var registry: StubMonitoredSubjectRegistry

    @Inject lateinit var meters: MeterRegistry

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
    fun `the schedule registers an approved case and a finding from the broker sends it to review`() {
        val journey = KybMonitoringJourney(parties, documents, jdbcUrl, jdbcUser, jdbcPassword)
        ensureTopics()
        val caseId = journey.approved("71000127")

        // Nothing here calls the relay: the scheduler does.
        await("the schedule to register both subjects") {
            journey.rows(caseId).size == 2 && journey.rows(caseId).all { it.syncState == "SYNCED" }
        }
        val case = journey.stored(caseId)
        val person = journey.rows(caseId).single { it.subjectRef != case.entityPartyId }.subjectRef
        assertThat(registry.registered.filter { it.subjectRef == person }.map { it.name }).containsOnly("Emil Plánovaný")
        assertThat(meters.find("openbank.kyb.monitoring.runs").tag("outcome", "completed").counter()?.count())
            .isGreaterThanOrEqualTo(1.0)

        val findingId = UUID.randomUUID()
        val finding = MonitoredSubjectMatchedWire.json(MonitoredSubjectMatchedWire.body(person, findingId))
        val foreign = MonitoredSubjectMatchedWire.json(
            MonitoredSubjectMatchedWire.body(person, ownerService = "openbank-kyc"),
        )
        val typeInBody = MonitoredSubjectMatchedWire.json(
            MonitoredSubjectMatchedWire.body(person) + ("eventType" to MonitoredSubjectMatchedWire.EVENT_TYPE),
        )
        producer().use { producer ->
            // One key, so one partition and one order: by the time the finding has been applied,
            // the two records before it have been through the consumer as well.
            producer.send(record(MonitoredSubjectMatchedWire.EVENT_TYPE, foreign)).get(SEND_SECONDS, TimeUnit.SECONDS)
            producer.send(ProducerRecord(MonitoredSubjectMatchedWire.TOPIC, KEY, typeInBody)).get(SEND_SECONDS, TimeUnit.SECONDS)
            producer.send(record("SanctionChecked", "not json")).get(SEND_SECONDS, TimeUnit.SECONDS)
            producer.send(record(MonitoredSubjectMatchedWire.EVENT_TYPE, finding)).get(SEND_SECONDS, TimeUnit.SECONDS)
            // The same finding redelivered, as at-least-once delivery will.
            producer.send(record(MonitoredSubjectMatchedWire.EVENT_TYPE, finding)).get(SEND_SECONDS, TimeUnit.SECONDS)
        }

        await("the case to be under a monitoring review") { journey.stored(caseId).status == "MANUAL_REVIEW" }
        await("the redelivery to be counted") { counted("duplicate") == 1.0 }
        val reviewed = journey.stored(caseId)
        assertThat(reviewed.reviewFrom).isEqualTo("SIGNED")
        assertThat(reviewed.screeningJson).contains(findingId.toString())
        // Exactly one finding recorded, although it was delivered twice and a foreign one named the same subject.
        assertThat(Regex("\"findingId\"").findAll(reviewed.screeningJson).count()).isEqualTo(1)
        assertThat(counted("review_opened")).isEqualTo(1.0)
        assertThat(counted("foreign_owner")).isEqualTo(1.0)
        assertThat(counted("unrecognised")).isEqualTo(1.0)
        assertThat(counted("ignored")).isEqualTo(1.0)
        assertThat(counted("malformed")).isEqualTo(0.0)
    }

    private fun record(eventType: String, body: String): ProducerRecord<String, String> {
        val entry = MonitoredSubjectMatchedWire.entry(eventType, body)
        // What the producer's publisher does: verbatim payload, the outbox headers.
        return ProducerRecord(MonitoredSubjectMatchedWire.TOPIC, KEY, entry.payload).also { record ->
            OutboxKafkaHeaders.headersFor(entry).forEach { (name, value) -> record.headers().add(name, value.toByteArray()) }
        }
    }

    private fun ensureTopics() {
        Admin.create(mapOf<String, Any>("bootstrap.servers" to bootstrap)).use { admin ->
            val existing = admin.listTopics().names().get(SEND_SECONDS, TimeUnit.SECONDS)
            val missing = TOPICS.filter { it !in existing }.map { NewTopic(it, 1, 1.toShort()) }
            if (missing.isNotEmpty()) admin.createTopics(missing).all().get(SEND_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun producer(): KafkaProducer<String, String> = KafkaProducer(
        mapOf<String, Any>(
            "bootstrap.servers" to bootstrap,
            "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "acks" to "all",
        ),
    )

    private fun counted(outcome: String): Double =
        meters.find(MicrometerKybMonitoringMetrics.FINDINGS).tag("outcome", outcome).counter()?.count() ?: -1.0

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(AWAIT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        error("timed out waiting for $what")
    }

    private companion object {
        const val KEY = "kyb-monitoring-wire-it"
        const val SEND_SECONDS = 20L
        const val AWAIT_SECONDS = 60L
        const val POLL_MS = 250L
        val TOPICS = listOf(
            MonitoredSubjectMatchedWire.TOPIC,
            "openbank.party.events",
            "openbank.kyb.events",
            "openbank.dlq.kyb.party-events-in",
            "openbank.dlq.kyb.sanctions-monitoring-in",
        )
    }
}
