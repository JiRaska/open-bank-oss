// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyb.application.usecase.MonitoringFindingOutcome
import com.openbank.kyb.application.usecase.MonitoringFindingService
import com.openbank.kyb.application.usecase.MonitoringSettings
import com.openbank.kyb.domain.model.MonitoringMatch
import com.openbank.kyb.domain.model.ScreeningVerdict
import com.openbank.kyb.infrastructure.MicrometerKybMonitoringMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Named so detekt's TooGenericExceptionThrown does not have to be suppressed at the throw site. */
private class CaseStoreFailure : RuntimeException("connection refused")

/**
 * Every record here has the producer's wire shape ([MonitoredSubjectMatchedWire]): the event type
 * on the `ce-type` header and a body without `eventType`. The meters are the real ones, so what
 * is asserted is what a scrape would show.
 */
class MonitoredSubjectMatchedConsumerTest {

    private val findings = mockk<MonitoringFindingService>()
    private val registry = SimpleMeterRegistry()
    private val settings = MonitoringSettings(false, 50, Duration.ofDays(7), "openbank-kyb")
    private val subjectRef = "5b0c2d3e-0000-4000-8000-0000000000aa"
    private lateinit var consumer: MonitoredSubjectMatchedConsumer

    @BeforeEach
    fun setUp() {
        consumer = MonitoredSubjectMatchedConsumer().also {
            it.findings = findings
            it.settings = settings
            it.metrics = MicrometerKybMonitoringMetrics(registry, settings).apply { register() }
            it.objectMapper = ObjectMapper()
            it.clock = Clock.fixed(Instant.parse("2026-11-13T12:00:05Z"), ZoneOffset.UTC)
        }
        coEvery { findings.handle(any(), any()) } returns MonitoringFindingOutcome.REVIEW_OPENED
    }

    private fun counted(outcome: String): Double =
        registry.find(MicrometerKybMonitoringMetrics.FINDINGS).tag("outcome", outcome).counter()?.count() ?: -1.0

    private fun matched(body: Map<String, Any>) = MonitoredSubjectMatchedWire.record(
        MonitoredSubjectMatchedWire.EVENT_TYPE,
        MonitoredSubjectMatchedWire.json(body),
    )

    @Test
    fun `the wire fixture carries exactly the producer contract's properties and no eventType`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("openbank-contracts")) }
        val doc: Map<String, Any> = Files.newInputStream(
            root.resolve("openbank-contracts/openbank-sanctions-service/asyncapi.yaml"),
        ).use { Yaml().load(it) }

        @Suppress("UNCHECKED_CAST")
        val schema = ((doc.getValue("components") as Map<String, Any>).getValue("schemas") as Map<String, Any>)
            .getValue("MonitoredSubjectMatchedPayload") as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val properties = (schema.getValue("properties") as Map<String, Any>).keys

        assertThat(MonitoredSubjectMatchedWire.body(subjectRef).keys).isEqualTo(properties)
        assertThat(properties).doesNotContain("eventType")
        // A closed schema: the producer cannot add a body discriminator without changing the contract.
        assertThat(schema["additionalProperties"]).isEqualTo(false)
    }

    @Test
    fun `every outcome an alert reads exists at zero before the first record`() {
        listOf("review_opened", "duplicate", "not_monitored", "unknown_subject", "foreign_owner", "unrecognised")
            .forEach { assertThat(counted(it)).describedAs(it).isEqualTo(0.0) }
    }

    @Test
    fun `a finding typed only by the ce-type header reaches the service with the entry it names`(): Unit = runBlocking {
        val findingId = UUID.randomUUID()

        consumer.consume(matched(MonitoredSubjectMatchedWire.body(subjectRef, findingId, matchStatus = "HIT")))

        val match = slot<MonitoringMatch>()
        coVerify(exactly = 1) { findings.handle(subjectRef, capture(match)) }
        assertThat(match.captured.findingId).isEqualTo(findingId)
        assertThat(match.captured.matchKind).isEqualTo("SANCTIONS")
        assertThat(match.captured.listType).isEqualTo("EU_CONSOLIDATED")
        assertThat(match.captured.listEntryRef).isEqualTo("id:synthetic-entry-1")
        assertThat(match.captured.matchScore).isEqualTo(0.93)
        assertThat(match.captured.verdict).isEqualTo(ScreeningVerdict.HIT)
        assertThat(match.captured.detection).isEqualTo("LIST_DELTA")
        assertThat(counted("review_opened")).isEqualTo(1.0)
        assertThat(counted("unrecognised")).isEqualTo(0.0)
    }

    @Test
    fun `the same body without the header is unrecognised and reaches nothing`(): Unit = runBlocking {
        val body = MonitoredSubjectMatchedWire.json(MonitoredSubjectMatchedWire.body(subjectRef))

        consumer.consume(MonitoredSubjectMatchedWire.record(body, emptyMap()))

        coVerify(exactly = 0) { findings.handle(any(), any()) }
        assertThat(counted("unrecognised")).isEqualTo(1.0)
    }

    @Test
    fun `a type moved into the body is unrecognised and reaches nothing`(): Unit = runBlocking {
        val body = MonitoredSubjectMatchedWire.body(subjectRef) + ("eventType" to "MONITORED_SUBJECT_MATCHED")

        consumer.consume(MonitoredSubjectMatchedWire.record(MonitoredSubjectMatchedWire.json(body), emptyMap()))

        coVerify(exactly = 0) { findings.handle(any(), any()) }
        assertThat(counted("unrecognised")).isEqualTo(1.0)
    }

    @Test
    fun `a finding owned by another service is not acted on, even for a reference this service holds`(): Unit =
        runBlocking {
            consumer.consume(matched(MonitoredSubjectMatchedWire.body(subjectRef, ownerService = "openbank-kyc")))
            // The shared fleet client is nobody's owner either.
            consumer.consume(matched(MonitoredSubjectMatchedWire.body(subjectRef, ownerService = "openbank-services")))

            coVerify(exactly = 0) { findings.handle(any(), any()) }
            assertThat(counted("foreign_owner")).isEqualTo(2.0)
            assertThat(counted("review_opened")).isEqualTo(0.0)
        }

    @Test
    fun `the events that share the topic are acked as ignored without their body being parsed`(): Unit = runBlocking {
        // Not JSON on purpose: an ignored sibling must never reach the decoder (it can hold names).
        listOf("SanctionChecked", "SanctionReviewed", "SANCTIONS_LIST_CHANGED", "SANCTIONS_LIST_CHANGE_STORM")
            .forEach { consumer.consume(MonitoredSubjectMatchedWire.record(it, "not json")) }

        coVerify(exactly = 0) { findings.handle(any(), any()) }
        assertThat(counted("ignored")).isEqualTo(4.0)
        assertThat(counted("malformed")).isEqualTo(0.0)
    }

    @Test
    fun `a record typed as a finding that cannot be one is malformed and nacked, never foreign`(): Unit = runBlocking {
        val broken = listOf(
            MonitoredSubjectMatchedWire.record(MonitoredSubjectMatchedWire.EVENT_TYPE, "{not json"),
            MonitoredSubjectMatchedWire.record(MonitoredSubjectMatchedWire.EVENT_TYPE, "[]"),
            matched(MonitoredSubjectMatchedWire.body(subjectRef) + ("findingId" to "not-a-uuid")),
            matched(MonitoredSubjectMatchedWire.body("")),
            // The producer dropped, renamed or blanked the owner: every finding of this service
            // would arrive like this, so it must not be counted as somebody else's.
            matched(MonitoredSubjectMatchedWire.body(subjectRef) - "ownerService"),
            matched(MonitoredSubjectMatchedWire.body(subjectRef, ownerService = " ")),
        )

        broken.forEach { record -> assertThrows<MalformedFindingException> { consumer.consume(record) } }

        coVerify(exactly = 0) { findings.handle(any(), any()) }
        assertThat(counted("malformed")).isEqualTo(broken.size.toDouble())
        assertThat(counted("foreign_owner")).isEqualTo(0.0)
    }

    @Test
    fun `what the service answered is what is counted`(): Unit = runBlocking {
        coEvery { findings.handle(any(), any()) } returnsMany listOf(
            MonitoringFindingOutcome.DUPLICATE,
            MonitoringFindingOutcome.UNKNOWN_SUBJECT,
            MonitoringFindingOutcome.NOT_MONITORED,
        )

        repeat(3) { consumer.consume(matched(MonitoredSubjectMatchedWire.body(subjectRef))) }

        assertThat(counted("duplicate")).isEqualTo(1.0)
        assertThat(counted("unknown_subject")).isEqualTo(1.0)
        assertThat(counted("not_monitored")).isEqualTo(1.0)
        assertThat(counted("review_opened")).isEqualTo(0.0)
    }

    @Test
    fun `a store failure is retried and then rethrown, so the record is not acked as handled`(): Unit = runBlocking {
        coEvery { findings.handle(any(), any()) } throws CaseStoreFailure()

        assertThrows<CaseStoreFailure> { consumer.consume(matched(MonitoredSubjectMatchedWire.body(subjectRef))) }

        coVerify(exactly = 3) { findings.handle(any(), any()) }
        assertThat(counted("review_opened")).isEqualTo(0.0)
    }
}
