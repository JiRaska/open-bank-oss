// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.security.application.port.out.IctIncidentRepository
import com.openbank.securityscanner.domain.IctIncident
import com.openbank.securityscanner.domain.IncidentCategory
import com.openbank.securityscanner.domain.IncidentSeverity
import com.openbank.securityscanner.domain.IncidentStatus
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import java.time.Clock
import java.time.Instant
import java.util.UUID

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

/**
 * The DORA ICT incident register.
 *
 * State lives in `ict_incidents` (issue #4728). It used to live in a per-pod `ConcurrentHashMap`,
 * which lost the whole register on every pod restart — not a staleness nuisance but data loss, and
 * one that needed no second replica to happen: a restart answered `GET /api/v1/ict-incidents` with
 * `[]` as though nothing had ever been reported, including incidents already flagged as reported to
 * the regulator. Unlike the compliance-pack registry of #3467 there was no row to converge onto, so
 * the fix had to be the row itself rather than a refresher.
 */
@ApplicationScoped
class IctIncidentService(
    @Channel("ict-incident-events-out") private val emitter: Emitter<Record<String, String>>,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val repository: IctIncidentRepository,
) {

    suspend fun reportIncident(cmd: ReportIncidentCommand): IctIncident {
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
        val saved = repository.save(incident)
        publishEvent("ICT_INCIDENT_REPORTED", saved)
        return saved
    }

    suspend fun updateStatus(
        id: UUID,
        status: IncidentStatus,
        containedAt: Instant?,
        resolvedAt: Instant?,
        rtoMinutes: Int?,
        rpoMinutes: Int?,
    ): IctIncident {
        val existing = repository.findIncident(id) ?: throw IctIncidentNotFoundException(id)
        val updated = repository.save(
            existing.copy(
                status = status,
                containedAt = containedAt ?: existing.containedAt,
                resolvedAt = resolvedAt ?: existing.resolvedAt,
                rtoMinutes = rtoMinutes ?: existing.rtoMinutes,
                rpoMinutes = rpoMinutes ?: existing.rpoMinutes,
                updatedAt = Instant.now(clock),
            ),
        )
        publishEvent("ICT_INCIDENT_STATUS_CHANGED", updated)
        return updated
    }

    suspend fun getIncident(id: UUID): IctIncident =
        repository.findIncident(id) ?: throw IctIncidentNotFoundException(id)

    suspend fun listIncidents(
        status: IncidentStatus?,
        severity: IncidentSeverity?,
        limit: Int,
        offset: Int,
    ): List<IctIncident> = repository.list(status, severity, limit, offset)

    suspend fun markReportedToRegulator(id: UUID, regulatoryReportId: String): IctIncident {
        val existing = repository.findIncident(id) ?: throw IctIncidentNotFoundException(id)
        val updated = repository.save(
            existing.copy(
                reportedToRegulator = true,
                regulatoryReportId = regulatoryReportId,
                updatedAt = Instant.now(clock),
            ),
        )
        publishEvent("ICT_INCIDENT_REPORTED_TO_REGULATOR", updated)
        return updated
    }

    private fun publishEvent(eventType: String, incident: IctIncident) {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to eventType,
                "incident" to incident,
                "occurredAt" to Instant.now(clock),
                "sourceService" to SOURCE_SERVICE,
            ),
        )
        emitter.send(Record.of(incident.id.toString(), payload))
    }

    companion object {
        /**
         * Producing service, read by `AuditConsumer.resolveSourceService` (audit-service) as the
         * strongest (EVENT-sourced) attribution — issue #3994/#5256. Before this field,
         * `TopicAttribution` already resolved `openbank.security.ict.incident` ->
         * `security-scanner` correctly, but only as TOPIC-sourced, not the producer's own claim —
         * and audit-service DOES subscribe to this topic today (it is in `application.yaml`'s
         * consumed-topics list), so this is a live attribution improvement, not a
         * forward-looking one. Value matches the fleet's audit convention: the module directory
         * without the `openbank-` prefix, the same spelling `TopicAttribution` already maps this
         * topic to.
         */
        internal const val SOURCE_SERVICE = "security-scanner"
    }
}
