// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.aml.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "aml_cases")
class AmlCaseEntity : PanacheEntity() {
    @Column(name = "case_id", nullable = false, unique = true)
    lateinit var caseId: UUID

    @Column(name = "idempotency_key", nullable = false, unique = true)
    lateinit var idempotencyKey: String

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "account_id")
    var accountId: UUID? = null

    @Column(name = "transaction_id")
    var transactionId: UUID? = null

    @Column(name = "customer_reference", nullable = false)
    lateinit var customerReference: String

    @Column(name = "screening_type", nullable = false)
    lateinit var screeningType: String

    @Column(name = "risk_level", nullable = false)
    lateinit var riskLevel: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "alert_code", nullable = false)
    lateinit var alertCode: String

    @Column(name = "alert_detail")
    var alertDetail: String? = null

    @Column(name = "matched_entity")
    var matchedEntity: String? = null

    @Column(name = "decision_reason")
    var decisionReason: String? = null

    @Column(name = "assigned_analyst")
    var assignedAnalyst: String? = null

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "screened_at", nullable = false)
    lateinit var screenedAt: Instant

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
