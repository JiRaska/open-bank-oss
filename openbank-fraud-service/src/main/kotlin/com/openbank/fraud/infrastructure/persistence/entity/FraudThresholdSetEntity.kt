// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence.entity

import com.openbank.libs.governance.ProposalState
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One version of the fraud money-threshold set and its four-eyes workflow state (ADR-0347,
 * `V9__fraud_threshold_set.sql`). Written with an explicit INSERT for a new proposal and a
 * conditional UPDATE for a decision — never `persist` on an existing row (the assigned-id
 * INSERT-only trap) and never a blind `merge` (the lost-update trap).
 */
@Entity
@Table(name = "fraud_threshold_set")
class FraudThresholdSetEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "version", nullable = false, updatable = false)
    var version: Int = 0

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    lateinit var state: ProposalState

    @Column(name = "thresholds_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    lateinit var thresholdsJson: String

    @Column(name = "content_hash", nullable = false, updatable = false)
    lateinit var contentHash: String

    @Column(name = "effective_from", updatable = false)
    var effectiveFrom: Instant? = null

    @Column(name = "active_from")
    var activeFrom: Instant? = null

    @Column(name = "proposed_by", nullable = false, updatable = false)
    lateinit var proposedBy: String

    @Column(name = "proposed_at", nullable = false, updatable = false)
    lateinit var proposedAt: Instant

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decision_reason")
    var decisionReason: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
