// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.security.application.port.out.IctIncidentRepository
import com.openbank.securityscanner.domain.IncidentCategory
import com.openbank.securityscanner.domain.IncidentSeverity
import com.openbank.securityscanner.domain.IncidentStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.reactive.messaging.kafka.Record
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Issue #3994/#5256: `IctIncidentService` is security-scanner's only live event producer (its
 * outbox apparatus was deleted entirely by #4709/#4940 — `V6__drop_security_outbox.sql`). It
 * publishes a hand-built map, not a serialised data class, straight to
 * `ict-incident-events-out` (`openbank.security.ict.incident`), so `sourceService` has to be
 * added at the map-construction call site rather than on a domain event type.
 *
 * `TopicAttribution` already resolves `openbank.security.ict.incident` -> `security-scanner`
 * correctly, but only as TOPIC-sourced — and audit-service DOES subscribe to this topic today
 * (it is in `application.yaml`'s consumed-topics list), so this is a live attribution
 * improvement, not a forward-looking one.
 */
class IctIncidentServiceTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC)
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val repository = mockk<IctIncidentRepository>()

    @Suppress("UNCHECKED_CAST")
    private fun mockEmitter(): Emitter<Record<String, String>> = mockk<Emitter<Record<String, String>>> {
        every { send(any()) } returns CompletableFuture.completedFuture(null)
    }

    @Test
    fun `reportIncident publishes sourceService on the ICT_INCIDENT_REPORTED event`(): Unit = runBlocking {
        val emitter = mockEmitter()
        val recordSlot = slot<Record<String, String>>()
        every { emitter.send(capture(recordSlot)) } returns CompletableFuture.completedFuture(null)
        coEvery { repository.save(any()) } answers { firstArg() }
        val service = IctIncidentService(emitter, objectMapper, fixedClock, repository)

        service.reportIncident(
            ReportIncidentCommand(
                title = "Core banking outage",
                description = "Ledger writes failing fleet-wide",
                category = IncidentCategory.AVAILABILITY,
                severity = IncidentSeverity.P1_CRITICAL,
                affectedServices = listOf("ledger-service"),
                detectedAt = Instant.now(fixedClock),
                assignedTo = null,
            ),
        )

        val payload = objectMapper.readTree(recordSlot.captured.value())
        assertThat(payload.get("eventType").asText()).isEqualTo("ICT_INCIDENT_REPORTED")
        assertThat(payload.get("sourceService").asText()).isEqualTo("security-scanner")
    }

    @Test
    fun `updateStatus publishes sourceService on the ICT_INCIDENT_STATUS_CHANGED event`(): Unit = runBlocking {
        val emitter = mockEmitter()
        val recordSlot = slot<Record<String, String>>()
        every { emitter.send(capture(recordSlot)) } returns CompletableFuture.completedFuture(null)
        val incidentId = UUID.randomUUID()
        val existing = existingIncident(incidentId)
        coEvery { repository.findIncident(incidentId) } returns existing
        coEvery { repository.save(any()) } answers { firstArg() }
        val service = IctIncidentService(emitter, objectMapper, fixedClock, repository)

        service.updateStatus(
            id = incidentId,
            status = IncidentStatus.CONTAINED,
            containedAt = Instant.now(fixedClock),
            resolvedAt = null,
            rtoMinutes = null,
            rpoMinutes = null,
        )

        val payload = objectMapper.readTree(recordSlot.captured.value())
        assertThat(payload.get("eventType").asText()).isEqualTo("ICT_INCIDENT_STATUS_CHANGED")
        assertThat(payload.get("sourceService").asText()).isEqualTo("security-scanner")
    }

    private fun existingIncident(id: UUID) = com.openbank.securityscanner.domain.IctIncident(
        id = id,
        title = "Core banking outage",
        description = "Ledger writes failing fleet-wide",
        category = IncidentCategory.AVAILABILITY,
        severity = IncidentSeverity.P1_CRITICAL,
        status = IncidentStatus.OPEN,
        affectedServices = listOf("ledger-service"),
        detectedAt = Instant.now(fixedClock),
        reportedAt = Instant.now(fixedClock),
        containedAt = null,
        resolvedAt = null,
        rtoMinutes = null,
        rpoMinutes = null,
        reportedToRegulator = false,
        regulatoryReportId = null,
        assignedTo = null,
        createdAt = Instant.now(fixedClock),
        updatedAt = Instant.now(fixedClock),
    )
}
