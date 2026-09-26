// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.security.application.port.out.IctIncidentRepository
import com.openbank.securityscanner.domain.IncidentCategory
import com.openbank.securityscanner.domain.IncidentSeverity
import com.openbank.securityscanner.domain.IncidentStatus
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Issue #3994/#5256: `IctIncidentService` is security-scanner's only live event producer (its
 * lifecycle event is now handed to the broker through the transactional ICT incident outbox.
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

    @Test
    fun `reportIncident persists a strictly ordered ICT_INCIDENT_REPORTED event`(): Unit = runBlocking {
        val eventSlot = slot<OutboxMessage>()
        coEvery { repository.save(any(), capture(eventSlot)) } answers { firstArg() }
        val service = IctIncidentService(objectMapper, fixedClock, repository)

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

        val payload = objectMapper.readTree(eventSlot.captured.payload)
        assertThat(payload.get("eventType").asText()).isEqualTo("ICT_INCIDENT_REPORTED")
        assertThat(payload.get("sourceService").asText()).isEqualTo("security-scanner")
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1)
        assertThat(payload.get("sourceVersion").asLong()).isPositive()
        assertThat(payload.get("aggregateRevision").asLong()).isEqualTo(1)
        assertThat(eventSlot.captured.createdAt).isEqualTo(fixedClock.instant())
    }

    @Test
    fun `updateStatus publishes sourceService on the ICT_INCIDENT_STATUS_CHANGED event`(): Unit = runBlocking {
        val eventSlot = slot<OutboxMessage>()
        val incidentId = UUID.randomUUID()
        val existing = existingIncident(incidentId)
        coEvery { repository.findIncident(incidentId) } returns existing
        coEvery { repository.save(any(), capture(eventSlot)) } answers { firstArg() }
        val service = IctIncidentService(objectMapper, fixedClock, repository)

        service.updateStatus(
            id = incidentId,
            status = IncidentStatus.CONTAINED,
            containedAt = Instant.now(fixedClock),
            resolvedAt = null,
            rtoMinutes = null,
            rpoMinutes = null,
        )

        val payload = objectMapper.readTree(eventSlot.captured.payload)
        assertThat(payload.get("eventType").asText()).isEqualTo("ICT_INCIDENT_STATUS_CHANGED")
        assertThat(payload.get("sourceService").asText()).isEqualTo("security-scanner")
        assertThat(payload.get("aggregateRevision").asLong()).isEqualTo(8)
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
        aggregateRevision = 7,
    )
}
