// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.securityscanner.domain.*
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import io.smallrye.reactive.messaging.kafka.Record
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ReportIncidentCommand(
    val title: String,
    val description: String,
    val category: IncidentCategory,
    val severity: IncidentSeverity,
    val affectedServices: List<String>,
    val detectedAt: Instant,
    val assignedTo: String?,
)

class IctIncidentNotFoundException(id: UUID) : RuntimeException("ICT incident not found: $id")

@ApplicationScoped
class IctIncidentService(
    @Channel("ict-incident-events-out") private val emitter: Emitter<Record<String, String>>,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val store = ConcurrentHashMap<UUID, IctIncident>()

    fun reportIncident(cmd: ReportIncidentCommand): IctIncident {
        val now = Instant.now(clock)
        val incident = IctIncident(
            id = UUID.randomUUID(),
            title = cmd.title,
            description = cmd.description,
            category = cmd.category,
            severity = cmd.severity,
            status = IncidentStatus.OPEN,
            affectedServices = cmd.affectedServices,
            detectedAt = cmd.detectedAt,
            reportedAt = now,
            containedAt = null,
            resolvedAt = null,
            rtoMinutes = null,
            rpoMinutes = null,
            reportedToRegulator = false,
            regulatoryReportId = null,
            assignedTo = cmd.assignedTo,
            createdAt = now,
            updatedAt = now,
        )
        store[incident.id] = incident
        publishEvent("ICT_INCIDENT_REPORTED", incident)
        return incident
    }

    fun updateStatus(
        id: UUID,
        status: IncidentStatus,
        containedAt: Instant?,
        resolvedAt: Instant?,
        rtoMinutes: Int?,
        rpoMinutes: Int?,
    ): IctIncident {
        val existing = store[id] ?: throw IctIncidentNotFoundException(id)
        val updated = existing.copy(
            status = status,
            containedAt = containedAt ?: existing.containedAt,
            resolvedAt = resolvedAt ?: existing.resolvedAt,
            rtoMinutes = rtoMinutes ?: existing.rtoMinutes,
            rpoMinutes = rpoMinutes ?: existing.rpoMinutes,
            updatedAt = Instant.now(clock),
        )
        store[id] = updated
        publishEvent("ICT_INCIDENT_STATUS_CHANGED", updated)
        return updated
    }

    fun getIncident(id: UUID): IctIncident = store[id] ?: throw IctIncidentNotFoundException(id)

    fun listIncidents(
        status: IncidentStatus?,
        severity: IncidentSeverity?,
        limit: Int,
        offset: Int,
    ): List<IctIncident> = store.values
        .filter { status == null || it.status == status }
        .filter { severity == null || it.severity == severity }
        .sortedByDescending { it.createdAt }
        .drop(offset)
        .take(limit.coerceIn(1, 200))

    fun markReportedToRegulator(id: UUID, regulatoryReportId: String): IctIncident {
        val existing = store[id] ?: throw IctIncidentNotFoundException(id)
        val updated = existing.copy(
            reportedToRegulator = true,
            regulatoryReportId = regulatoryReportId,
            updatedAt = Instant.now(clock),
        )
        store[id] = updated
        publishEvent("ICT_INCIDENT_REPORTED_TO_REGULATOR", updated)
        return updated
    }

    private fun publishEvent(eventType: String, incident: IctIncident) {
        val payload = objectMapper.writeValueAsString(
            mapOf("eventType" to eventType, "incident" to incident, "occurredAt" to Instant.now(clock)),
        )
        emitter.send(Record.of(incident.id.toString(), payload))
    }
}
