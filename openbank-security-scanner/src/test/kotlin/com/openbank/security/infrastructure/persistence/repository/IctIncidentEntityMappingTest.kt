// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.security.infrastructure.persistence.repository

import com.openbank.security.infrastructure.persistence.entity.IctIncidentEntity
import com.openbank.securityscanner.domain.IctIncident
import com.openbank.securityscanner.domain.IncidentCategory
import com.openbank.securityscanner.domain.IncidentSeverity
import com.openbank.securityscanner.domain.IncidentStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The domain <-> row mapping of the ICT incident register (issue #4728), exercised without a
 * database: the two converters are pure functions, and the one that has actually been wrong in
 * this codebase before is `affectedServices` — `"".split(",")` yields `[""]`, not `[]`, so an
 * incident with no affected services must be proven to round-trip as an empty list.
 *
 * The converters are private members of [IctIncidentRepositoryImpl]; the alternative to reflection
 * here is a Testcontainers integration test (`IctIncidentDurabilityIT`), which cannot run as a
 * fast unit test.
 */
class IctIncidentEntityMappingTest {

    private val repository = IctIncidentRepositoryImpl()
    private val now = Instant.parse("2026-08-16T10:00:00Z")

    private fun toEntity(incident: IctIncident): IctIncidentEntity {
        val m = IctIncidentRepositoryImpl::class.java
            .getDeclaredMethod("toEntity", IctIncident::class.java)
        m.isAccessible = true
        return m.invoke(repository, incident) as IctIncidentEntity
    }

    private fun toDomain(entity: IctIncidentEntity): IctIncident {
        val m = IctIncidentRepositoryImpl::class.java
            .getDeclaredMethod("toDomain", IctIncidentEntity::class.java)
        m.isAccessible = true
        return m.invoke(repository, entity) as IctIncident
    }

    @Test
    fun `a fully populated incident round-trips unchanged`() {
        val incident = incident(
            affectedServices = listOf("ledger-service", "billing-service"),
            containedAt = now.plusSeconds(600),
            resolvedAt = now.plusSeconds(1200),
            rtoMinutes = 30,
            rpoMinutes = 5,
            reportedToRegulator = true,
            regulatoryReportId = "CNB-2026-0042",
            assignedTo = "sre-oncall",
        )

        assertThat(toDomain(toEntity(incident))).isEqualTo(incident)
    }

    @Test
    fun `enums are stored as their names and parsed back`() {
        val incident = incident(affectedServices = listOf("ledger-service")).copy(
            category = IncidentCategory.SUPPLY_CHAIN,
            severity = IncidentSeverity.P3_MEDIUM,
            status = IncidentStatus.INVESTIGATING,
        )

        val entity = toEntity(incident)

        assertThat(entity.category).isEqualTo("SUPPLY_CHAIN")
        assertThat(entity.severity).isEqualTo("P3_MEDIUM")
        assertThat(entity.status).isEqualTo("INVESTIGATING")
        val back = toDomain(entity)
        assertThat(back.category).isEqualTo(IncidentCategory.SUPPLY_CHAIN)
        assertThat(back.severity).isEqualTo(IncidentSeverity.P3_MEDIUM)
        assertThat(back.status).isEqualTo(IncidentStatus.INVESTIGATING)
    }

    @Test
    fun `no affected services round-trips as an empty list not a list holding one empty string`() {
        val entity = toEntity(incident(affectedServices = emptyList()))

        assertThat(entity.affectedServices).isEmpty()
        assertThat(toDomain(entity).affectedServices).isEmpty()
    }

    @Test
    fun `affected services are comma-joined and split back`() {
        val entity = toEntity(incident(affectedServices = listOf("a", "b", "c")))

        assertThat(entity.affectedServices).isEqualTo("a,b,c")
        assertThat(toDomain(entity).affectedServices).containsExactly("a", "b", "c")
    }

    @Test
    fun `nullable columns stay null through the mapping`() {
        val entity = toEntity(incident(affectedServices = listOf("ledger-service")))

        assertThat(entity.containedAt).isNull()
        assertThat(entity.resolvedAt).isNull()
        assertThat(entity.rtoMinutes).isNull()
        assertThat(entity.rpoMinutes).isNull()
        assertThat(entity.regulatoryReportId).isNull()
        assertThat(entity.assignedTo).isNull()
        assertThat(entity.reportedToRegulator).isFalse()

        val back = toDomain(entity)
        assertThat(back.containedAt).isNull()
        assertThat(back.resolvedAt).isNull()
        assertThat(back.assignedTo).isNull()
    }

    @Suppress("LongParameterList")
    private fun incident(
        affectedServices: List<String>,
        containedAt: Instant? = null,
        resolvedAt: Instant? = null,
        rtoMinutes: Int? = null,
        rpoMinutes: Int? = null,
        reportedToRegulator: Boolean = false,
        regulatoryReportId: String? = null,
        assignedTo: String? = null,
    ) = IctIncident(
        id = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        title = "Ledger writes failing",
        description = "Fleet-wide",
        category = IncidentCategory.AVAILABILITY,
        severity = IncidentSeverity.P1_CRITICAL,
        status = IncidentStatus.OPEN,
        affectedServices = affectedServices,
        detectedAt = now.minusSeconds(300),
        reportedAt = now,
        containedAt = containedAt,
        resolvedAt = resolvedAt,
        rtoMinutes = rtoMinutes,
        rpoMinutes = rpoMinutes,
        reportedToRegulator = reportedToRegulator,
        regulatoryReportId = regulatoryReportId,
        assignedTo = assignedTo,
        createdAt = now,
        updatedAt = now,
    )
}
