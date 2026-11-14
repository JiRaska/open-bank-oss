// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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

    /** Null while the owning party is not established (V15); see `AmlCase.partyId`. */
    @Column(name = "party_id")
    var partyId: UUID? = null

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

    /** #10916: request fingerprint the case was created under; null for rows predating V9. */
    @Column(name = "request_hash", length = 64)
    var requestHash: String? = null

    /** `CaseOrigin` name; null for rows written before V15 or by a pod that predates it. */
    @Column(name = "origin", length = 32)
    var origin: String? = null

    /** When the resolution sweep last tried and failed to find this case's party; orders the sweep. */
    @Column(name = "party_resolution_attempted_at")
    var partyResolutionAttemptedAt: Instant? = null

    // V17, ADR-0374 point 3: the four-eyes proposal of a held payment's case. Nullable and written
    // only by the hold-decision routes; a pod without these mappings never reads or writes them.
    @Column(name = "hold_proposal_outcome", length = 16)
    var holdProposalOutcome: String? = null

    @Column(name = "hold_proposal_reason")
    var holdProposalReason: String? = null

    @Column(name = "hold_proposed_by")
    var holdProposedBy: String? = null

    @Column(name = "hold_proposed_at")
    var holdProposedAt: Instant? = null

    // V18: the proposer's PERSON (four-eyes compares persons, not accounts), the proposal's expiry,
    // and its withdrawal by the proposer.
    @Column(name = "hold_proposer_person_id", length = 64)
    var holdProposerPersonId: String? = null

    @Column(name = "hold_proposal_expires_at")
    var holdProposalExpiresAt: Instant? = null

    @Column(name = "hold_proposal_withdrawn_by")
    var holdProposalWithdrawnBy: String? = null

    @Column(name = "hold_proposal_withdrawn_at")
    var holdProposalWithdrawnAt: Instant? = null
}
