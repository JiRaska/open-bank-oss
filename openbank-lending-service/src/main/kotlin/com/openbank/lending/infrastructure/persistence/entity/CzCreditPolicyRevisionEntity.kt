// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.infrastructure.persistence.entity

import com.openbank.libs.governance.ProposalState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "cz_credit_policy_revision")
class CzCreditPolicyRevisionEntity {
    @Id
    lateinit var id: UUID

    var revision: Int = 0

    @Column(name = "effective_from")
    lateinit var effectiveFrom: LocalDate

    lateinit var payload: String

    @Column(name = "content_hash")
    lateinit var contentHash: String

    @Enumerated(EnumType.STRING)
    lateinit var state: ProposalState

    @Column(name = "proposed_by")
    lateinit var proposedBy: String

    @Column(name = "proposed_at")
    lateinit var proposedAt: OffsetDateTime

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: OffsetDateTime? = null

    @Column(name = "decision_reason")
    var decisionReason: String? = null
}
