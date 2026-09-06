// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.openbank.security.application.port.out.IctIncidentRepository
import com.openbank.securityscanner.domain.IctIncident
import com.openbank.securityscanner.domain.IncidentCategory
import com.openbank.securityscanner.domain.IncidentSeverity
import com.openbank.securityscanner.domain.IncidentStatus
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.reactive.messaging.kafka.Record
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Behaviour of the DORA ICT incident register's use cases (issue #4728): what each command writes
 * to the repository, which transitions preserve prior timestamps, and which reads raise
 * [IctIncidentNotFoundException]. Complements `IctIncidentServiceTest`, which covers only the
 * `sourceService` attribution on two of the events.
 */
class IctIncidentServiceBehaviourTest {

    private val now = Instant.parse("2026-08-16T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    // findAndRegisterModules() alone is NOT the production shape: plain Jackson leaves
    // WRITE_DATES_AS_TIMESTAMPS enabled, so an Instant goes on the wire as an epoch float
    // ("1.7868744E9") instead of ISO-8601. Quarkus disables it on the mapper it injects, so the
    // test mapper has to disable it too or it asserts against a wire format nothing produces.
    private val objectMapper = ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val repository = mockk<IctIncidentRepository>()
    private val emitter = mockk<Emitter<Record<String, String>>>()
    private val service = IctIncidentService(emitter, objectMapper, clock, repository)

    private fun captureRecord(): CapturingSlot<Record<String, String>> {
        val slot = slot<Record<String, String>>()
        every { emitter.send(capture(slot)) } returns CompletableFuture.completedFuture(null)
        return slot
    }

    @Test
    fun `reportIncident opens the incident at the clock instant and keeps the detection time`(): Unit = runBlocking {
        captureRecord()
        val saved = slot<IctIncident>()
        coEvery { repository.save(capture(saved)) } answers { firstArg() }
        val detected = Instant.parse("2026-08-16T09:12:00Z")

        val incident = service.reportIncident(
            ReportIncidentCommand(
                title = "Ledger writes failing",
                description = "Fleet-wide",
                category = IncidentCategory.AVAILABILITY,
                severity = IncidentSeverity.P1_CRITICAL,
                affectedServices = listOf("ledger-service", "billing-service"),
                detectedAt = detected,
                assignedTo = "sre-oncall",
            ),
        )

        assertThat(saved.captured).isSameAs(incident)
        assertThat(incident.status).isEqualTo(IncidentStatus.OPEN)
        assertThat(incident.detectedAt).isEqualTo(detected)
        assertThat(incident.reportedAt).isEqualTo(now)
        assertThat(incident.createdAt).isEqualTo(now)
        assertThat(incident.updatedAt).isEqualTo(now)
        assertThat(incident.containedAt).isNull()
        assertThat(incident.resolvedAt).isNull()
        assertThat(incident.reportedToRegulator).isFalse()
        assertThat(incident.regulatoryReportId).isNull()
        assertThat(incident.assignedTo).isEqualTo("sre-oncall")
        assertThat(incident.affectedServices).containsExactly("ledger-service", "billing-service")
    }

    @Test
    fun `two reports get distinct ids`(): Unit = runBlocking {
        captureRecord()
        coEvery { repository.save(any()) } answers { firstArg() }

        val first = service.reportIncident(command())
        val second = service.reportIncident(command())

        assertThat(first.id).isNotEqualTo(second.id)
    }

    @Test
    fun `updateStatus keeps existing timestamps when the command omits them`(): Unit = runBlocking {
        captureRecord()
        val id = UUID.randomUUID()
        val existing = existing(id).copy(
            containedAt = Instant.parse("2026-08-16T09:30:00Z"),
            rtoMinutes = 45,
            rpoMinutes = 5,
            updatedAt = Instant.parse("2026-08-16T09:30:00Z"),
        )
        coEvery { repository.findIncident(id) } returns existing
        coEvery { repository.save(any()) } answers { firstArg() }

        val updated = service.updateStatus(id, IncidentStatus.RESOLVED, null, null, null, null)

        assertThat(updated.status).isEqualTo(IncidentStatus.RESOLVED)
        assertThat(updated.containedAt).isEqualTo(existing.containedAt)
        assertThat(updated.rtoMinutes).isEqualTo(45)
        assertThat(updated.rpoMinutes).isEqualTo(5)
        assertThat(updated.resolvedAt).isNull()
        assertThat(updated.updatedAt).isEqualTo(now)
    }

    @Test
    fun `updateStatus overwrites timestamps and recovery objectives when supplied`(): Unit = runBlocking {
        captureRecord()
        val id = UUID.randomUUID()
        coEvery { repository.findIncident(id) } returns existing(id)
        coEvery { repository.save(any()) } answers { firstArg() }
        val contained = Instant.parse("2026-08-16T09:45:00Z")
        val resolved = Instant.parse("2026-08-16T09:55:00Z")

        val updated = service.updateStatus(id, IncidentStatus.RESOLVED, contained, resolved, 30, 10)

        assertThat(updated.containedAt).isEqualTo(contained)
        assertThat(updated.resolvedAt).isEqualTo(resolved)
        assertThat(updated.rtoMinutes).isEqualTo(30)
        assertThat(updated.rpoMinutes).isEqualTo(10)
    }

    @Test
    fun `updateStatus on an unknown id neither saves nor publishes`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repository.findIncident(id) } returns null

        assertThatThrownBy {
            runBlocking { service.updateStatus(id, IncidentStatus.CLOSED, null, null, null, null) }
        }
            .isInstanceOf(IctIncidentNotFoundException::class.java)
            .hasMessageContaining(id.toString())

        coVerify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { emitter.send(any()) }
    }

    @Test
    fun `getIncident returns the stored incident and raises for an unknown id`(): Unit = runBlocking {
        val known = UUID.randomUUID()
        val unknown = UUID.randomUUID()
        coEvery { repository.findIncident(known) } returns existing(known)
        coEvery { repository.findIncident(unknown) } returns null

        assertThat(service.getIncident(known).id).isEqualTo(known)
        assertThatThrownBy { runBlocking { service.getIncident(unknown) } }
            .isInstanceOf(IctIncidentNotFoundException::class.java)
    }

    @Test
    fun `listIncidents passes the filters straight through to the repository`(): Unit = runBlocking {
        val expected = listOf(existing(UUID.randomUUID()))
        coEvery {
            repository.list(IncidentStatus.OPEN, IncidentSeverity.P2_HIGH, 25, 50)
        } returns expected

        val result = service.listIncidents(IncidentStatus.OPEN, IncidentSeverity.P2_HIGH, 25, 50)

        assertThat(result).isEqualTo(expected)
        coVerify(exactly = 1) { repository.list(IncidentStatus.OPEN, IncidentSeverity.P2_HIGH, 25, 50) }
    }

    @Test
    fun `markReportedToRegulator flips the flag and publishes the regulator event`(): Unit = runBlocking {
        val slot = captureRecord()
        val id = UUID.randomUUID()
        coEvery { repository.findIncident(id) } returns existing(id)
        coEvery { repository.save(any()) } answers { firstArg() }

        val updated = service.markReportedToRegulator(id, "CNB-2026-0042")

        assertThat(updated.reportedToRegulator).isTrue()
        assertThat(updated.regulatoryReportId).isEqualTo("CNB-2026-0042")
        assertThat(updated.updatedAt).isEqualTo(now)
        assertThat(updated.status).isEqualTo(IncidentStatus.OPEN)

        val payload = objectMapper.readTree(slot.captured.value())
        assertThat(payload.get("eventType").asText()).isEqualTo("ICT_INCIDENT_REPORTED_TO_REGULATOR")
        assertThat(payload.get("occurredAt").asText()).startsWith("2026-08-16T10:00:00")
        assertThat(payload.get("incident").get("regulatoryReportId").asText()).isEqualTo("CNB-2026-0042")
        assertThat(slot.captured.key()).isEqualTo(id.toString())
    }

    @Test
    fun `markReportedToRegulator on an unknown id raises before writing`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repository.findIncident(id) } returns null

        assertThatThrownBy { runBlocking { service.markReportedToRegulator(id, "CNB-1") } }
            .isInstanceOf(IctIncidentNotFoundException::class.java)

        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `the published event carries the incident the repository returned, not the one submitted`(): Unit =
        runBlocking {
            val slot = captureRecord()
            // The repository is the record of truth: whatever it hands back is what goes on the wire.
            coEvery { repository.save(any()) } answers {
                (firstArg() as IctIncident).copy(title = "normalised-by-store")
            }

            val incident = service.reportIncident(command())

            assertThat(incident.title).isEqualTo("normalised-by-store")
            val payload = objectMapper.readTree(slot.captured.value())
            assertThat(payload.get("incident").get("title").asText()).isEqualTo("normalised-by-store")
            assertThat(payload.get("sourceService").asText()).isEqualTo(IctIncidentService.SOURCE_SERVICE)
        }

    private fun command() = ReportIncidentCommand(
        title = "Ledger writes failing",
        description = "Fleet-wide",
        category = IncidentCategory.AVAILABILITY,
        severity = IncidentSeverity.P1_CRITICAL,
        affectedServices = listOf("ledger-service"),
        detectedAt = now,
        assignedTo = null,
    )

    private fun existing(id: UUID) = IctIncident(
        id = id,
        title = "Ledger writes failing",
        description = "Fleet-wide",
        category = IncidentCategory.AVAILABILITY,
        severity = IncidentSeverity.P1_CRITICAL,
        status = IncidentStatus.OPEN,
        affectedServices = listOf("ledger-service"),
        detectedAt = now,
        reportedAt = now,
        containedAt = null,
        resolvedAt = null,
        rtoMinutes = null,
        rpoMinutes = null,
        reportedToRegulator = false,
        regulatoryReportId = null,
        assignedTo = null,
        createdAt = now,
        updatedAt = now,
    )
}
