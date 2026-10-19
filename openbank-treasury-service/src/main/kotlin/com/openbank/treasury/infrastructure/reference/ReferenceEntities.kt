// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.reference

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate

/* Every column named explicitly (entity-column-names gate): this service sets no naming strategy. */

@Entity
@Table(name = "reference_records")
class ReferenceRecordEntity : PanacheEntity() {
    @Column(name = "kind", nullable = false)
    lateinit var kind: String

    @Column(name = "record_key", nullable = false)
    lateinit var recordKey: String

    @Column(name = "version", nullable = false)
    var version: Int = 0

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "valid_from", nullable = false)
    lateinit var validFrom: LocalDate

    @Column(name = "valid_to")
    var validTo: LocalDate? = null

    @Column(name = "verified", nullable = false)
    var verified: Boolean = false

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    lateinit var payload: String

    @Column(name = "created_by", nullable = false)
    lateinit var createdBy: String

    @Column(name = "created_by_type", nullable = false)
    lateinit var createdByType: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "reason", columnDefinition = "TEXT")
    var reason: String? = null

    @Column(name = "approved_by")
    var approvedBy: String? = null

    @Column(name = "approved_by_type")
    var approvedByType: String? = null

    @Column(name = "approved_at")
    var approvedAt: Instant? = null

    @Column(name = "verified_by")
    var verifiedBy: String? = null

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    var decisionReason: String? = null

    @Column(name = "approval_note", columnDefinition = "TEXT")
    var approvalNote: String? = null

    /** Optimistic lock (V21): a second writer that read the row before this one committed fails at flush. */
    @Version
    @Column(name = "row_version", nullable = false)
    var rowVersion: Long = 0
}

/**
 * Keyed by the client's Idempotency-Key itself (application-assigned @Id): written with `persist`
 * exactly once, never updated — so persist's INSERT-only semantics are what we want here.
 */
@Entity
@Table(name = "reference_commands")
class ReferenceCommandEntity {
    @Id
    @Column(name = "idempotency_key", nullable = false)
    lateinit var idempotencyKey: String

    @Column(name = "action", nullable = false)
    lateinit var action: String

    @Column(name = "kind", nullable = false)
    lateinit var kind: String

    @Column(name = "record_key", nullable = false)
    lateinit var recordKey: String

    @Column(name = "version", nullable = false)
    var version: Int = 0

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}
