// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyc.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "kyc_screening_triggers")
class ScreeningTriggerEntity : PanacheEntity() {

    @Column(name = "trigger_id", nullable = false, unique = true)
    lateinit var triggerId: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "trigger_type", nullable = false)
    lateinit var triggerType: String

    @Column(name = "publication_id", nullable = false)
    lateinit var publicationId: UUID

    @Column(name = "chunk_index")
    var chunkIndex: Long? = null

    @Column(name = "list_type")
    var listType: String? = null

    /** JSON document, TEXT not JSONB — see the migration note (Vert.x PG client ClassCastException). */
    @Column(name = "diff", nullable = false, columnDefinition = "TEXT")
    lateinit var diff: String

    @Column(name = "state", nullable = false)
    lateinit var state: String

    @Column(name = "coordinator_case_id")
    var coordinatorCaseId: String? = null

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0

    @Column(name = "failed_reason")
    var failedReason: String? = null

    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null
}
