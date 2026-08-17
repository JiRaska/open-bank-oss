// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The durable row behind the DORA ICT incident register (issue #4728).
 *
 * [PanacheEntityBase] with an explicit [Id], not [io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity]:
 * the id is assigned by the application (`UUID.randomUUID()` in `IctIncidentService.reportIncident`),
 * so there is no `@GeneratedValue` and no `ict_incidents_seq`. That choice has a consequence the
 * repository must honour — with a non-null assigned id Hibernate cannot tell transient from
 * detached, so `persist()` schedules an INSERT for *every* save and each status transition would
 * fail at flush with `duplicate key value violates ... _pkey`. Updates therefore go through
 * `Panache.getSession().flatMap { it.merge(entity) }`; see `IctIncidentRepositoryImpl.save`.
 *
 * `affectedServices` is a comma-joined TEXT column rather than an element collection: the list is a
 * small, opaque, display-only set of service names that nothing queries by, so a join table would
 * add a migration and a fetch strategy for no read anyone performs.
 */
@Entity
@Table(name = "ict_incidents")
class IctIncidentEntity : PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    lateinit var title: String

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    lateinit var description: String

    @Column(name = "category", nullable = false)
    lateinit var category: String

    @Column(name = "severity", nullable = false)
    lateinit var severity: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    /** Comma-joined service names; empty string means "none recorded". */
    @Column(name = "affected_services", nullable = false, columnDefinition = "TEXT")
    lateinit var affectedServices: String

    @Column(name = "detected_at", nullable = false)
    lateinit var detectedAt: Instant

    @Column(name = "reported_at", nullable = false)
    lateinit var reportedAt: Instant

    @Column(name = "contained_at")
    var containedAt: Instant? = null

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null

    @Column(name = "rto_minutes")
    var rtoMinutes: Int? = null

    @Column(name = "rpo_minutes")
    var rpoMinutes: Int? = null

    @Column(name = "reported_to_regulator", nullable = false)
    var reportedToRegulator: Boolean = false

    @Column(name = "regulatory_report_id", columnDefinition = "TEXT")
    var regulatoryReportId: String? = null

    @Column(name = "assigned_to", columnDefinition = "TEXT")
    var assignedTo: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
