// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "dispatch_resume_proposal")
class DispatchResumeProposalEntity : PanacheEntity() {
    @Column(name = "proposal_id", nullable = false, unique = true)
    lateinit var proposalId: String

    @Column(name = "control_key", nullable = false)
    lateinit var controlKey: String

    @Column(name = "reason", columnDefinition = "TEXT")
    var reason: String? = null

    @Column(name = "proposed_by", nullable = false)
    lateinit var proposedBy: String

    @Column(name = "proposed_at", nullable = false)
    lateinit var proposedAt: Instant

    @Column(name = "state", nullable = false)
    lateinit var state: String

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    var decisionReason: String? = null

    @Column(name = "executed_at")
    var executedAt: Instant? = null
}
