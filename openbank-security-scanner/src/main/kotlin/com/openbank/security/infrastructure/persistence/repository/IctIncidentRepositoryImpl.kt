// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.infrastructure.persistence.repository

import com.openbank.security.application.port.out.IctIncidentRepository
import com.openbank.security.infrastructure.persistence.entity.IctIncidentEntity
import com.openbank.securityscanner.domain.IctIncident
import com.openbank.securityscanner.domain.IncidentCategory
import com.openbank.securityscanner.domain.IncidentSeverity
import com.openbank.securityscanner.domain.IncidentStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.quarkus.panache.common.Parameters
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class IctIncidentRepositoryImpl :
    IctIncidentRepository,
    PanacheRepositoryBase<IctIncidentEntity, UUID> {

    /**
     * `merge`, never `persist`. The id is application-assigned, so a non-null id cannot tell
     * Hibernate transient from detached: `persist()` schedules an INSERT unconditionally and every
     * status transition would 500 with `duplicate key value violates "ict_incidents_pkey"` at
     * flush. `merge` is the upsert the assigned-id case requires, and it makes the initial report
     * and the later transitions the same code path.
     */
    override suspend fun save(incident: IctIncident): IctIncident = Panache.withTransaction {
        Panache.getSession().flatMap { session -> session.merge(incident.toEntity()) }
    }.awaitSuspending().toDomain()

    override suspend fun findIncident(id: UUID): IctIncident? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun list(
        status: IncidentStatus?,
        severity: IncidentSeverity?,
        limit: Int,
        offset: Int,
    ): List<IctIncident> {
        val clauses = mutableListOf<String>()
        val params = Parameters()
        status?.let {
            clauses += "status = :status"
            params.and("status", it.name)
        }
        severity?.let {
            clauses += "severity = :severity"
            params.and("severity", it.name)
        }
        val where = if (clauses.isEmpty()) "" else clauses.joinToString(" and ") + " "
        val capped = limit.coerceIn(1, MAX_PAGE)
        val from = offset.coerceAtLeast(0)
        return Panache.withSession {
            find("$where order by createdAt desc", params)
                .range(from, from + capped - 1)
                .list()
        }.awaitSuspending().map { it.toDomain() }
    }

    private fun IctIncident.toEntity(): IctIncidentEntity = IctIncidentEntity().also { e ->
        e.id = id
        e.title = title
        e.description = description
        e.category = category.name
        e.severity = severity.name
        e.status = status.name
        e.affectedServices = affectedServices.joinToString(SEP)
        e.detectedAt = detectedAt
        e.reportedAt = reportedAt
        e.containedAt = containedAt
        e.resolvedAt = resolvedAt
        e.rtoMinutes = rtoMinutes
        e.rpoMinutes = rpoMinutes
        e.reportedToRegulator = reportedToRegulator
        e.regulatoryReportId = regulatoryReportId
        e.assignedTo = assignedTo
        e.createdAt = createdAt
        e.updatedAt = updatedAt
    }

    private fun IctIncidentEntity.toDomain(): IctIncident = IctIncident(
        id = id,
        title = title,
        description = description,
        category = IncidentCategory.valueOf(category),
        severity = IncidentSeverity.valueOf(severity),
        status = IncidentStatus.valueOf(status),
        // split() on "" yields [""], not [] — an incident with no affected services must round-trip
        // as an empty list, not as a list holding one empty string.
        affectedServices = affectedServices.takeIf { it.isNotEmpty() }?.split(SEP) ?: emptyList(),
        detectedAt = detectedAt,
        reportedAt = reportedAt,
        containedAt = containedAt,
        resolvedAt = resolvedAt,
        rtoMinutes = rtoMinutes,
        rpoMinutes = rpoMinutes,
        reportedToRegulator = reportedToRegulator,
        regulatoryReportId = regulatoryReportId,
        assignedTo = assignedTo,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private companion object {
        const val MAX_PAGE = 200
        const val SEP = ","
    }
}
