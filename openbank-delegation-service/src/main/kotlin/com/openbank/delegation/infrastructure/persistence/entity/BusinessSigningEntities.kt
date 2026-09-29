// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// ADR-0312 persistence. Every column is named explicitly (check-entity-column-names.py): Hibernate's
// implicit name for a multi-word property is not the snake_case column V25 creates.

@Entity
@Table(name = "signing_policies")
class SigningPolicyEntity : PanacheEntityBase() {
    @Id
    @Column(name = "entity_party_id", nullable = false, updatable = false)
    lateinit var entityPartyId: UUID

    @Column(name = "version", nullable = false)
    var version: Int = 1

    @Column(name = "rules_json", nullable = false)
    lateinit var rulesJson: String

    @Column(name = "trusted_payee_cap_amount", precision = 19, scale = 4)
    var trustedPayeeCapAmount: BigDecimal? = null

    @Column(name = "trusted_payee_cap_currency", length = 3)
    var trustedPayeeCapCurrency: String? = null

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @Column(name = "updated_by_approval_id")
    var updatedByApprovalId: UUID? = null
}

@Entity
@Table(name = "signer_groups")
class SignerGroupEntity : PanacheEntityBase() {
    @Id
    @Column(name = "row_id", nullable = false, updatable = false)
    lateinit var rowId: UUID

    @Column(name = "id", nullable = false, updatable = false, length = 64)
    lateinit var id: String

    @Column(name = "entity_party_id", nullable = false, updatable = false)
    lateinit var entityPartyId: UUID

    @Column(name = "name", nullable = false, length = 120)
    lateinit var name: String

    @Column(name = "member_party_ids", nullable = false)
    lateinit var memberPartyIds: String

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @Column(name = "updated_by_approval_id", nullable = false)
    lateinit var updatedByApprovalId: UUID
}

@Entity
@Table(name = "trusted_payees")
class TrustedPayeeEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "entity_party_id", nullable = false, updatable = false)
    lateinit var entityPartyId: UUID

    @Column(name = "iban", nullable = false, updatable = false, length = 34)
    lateinit var iban: String

    @Column(name = "name", nullable = false, length = 140)
    lateinit var name: String

    @Column(name = "bic", length = 11)
    var bic: String? = null

    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @Column(name = "added_at", nullable = false, updatable = false)
    lateinit var addedAt: Instant

    @Column(name = "added_by_approval_id", nullable = false, updatable = false)
    lateinit var addedByApprovalId: UUID

    @Column(name = "removed_at")
    var removedAt: Instant? = null

    @Column(name = "removed_by_approval_id")
    var removedByApprovalId: UUID? = null
}

@Entity
@Table(name = "approval_requests")
class ApprovalRequestEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "entity_party_id", nullable = false, updatable = false)
    lateinit var entityPartyId: UUID

    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    lateinit var kind: String

    @Column(name = "payload", nullable = false, updatable = false)
    lateinit var payload: String

    @Column(name = "payload_sha256", nullable = false, updatable = false, length = 64)
    lateinit var payloadSha256: String

    @Column(name = "summary_json", updatable = false)
    var summaryJson: String? = null

    @Column(name = "policy_version", nullable = false, updatable = false)
    var policyVersion: Int = 0

    @Column(name = "required_signatures", nullable = false, updatable = false)
    var requiredSignatures: Int = 1

    @Column(name = "eligible_signer_ids", nullable = false, updatable = false)
    lateinit var eligibleSignerIds: String

    @Column(name = "must_include_group_id", updatable = false, length = 64)
    var mustIncludeGroupId: String? = null

    @Column(name = "must_include_signer_ids", updatable = false)
    var mustIncludeSignerIds: String? = null

    @Column(name = "initiator_party_id", nullable = false, updatable = false)
    lateinit var initiatorPartyId: UUID

    @Column(name = "entity_name", updatable = false, length = 200)
    var entityName: String? = null

    @Column(name = "initiator_name", updatable = false, length = 200)
    var initiatorName: String? = null

    @Column(name = "status", nullable = false, length = 24)
    lateinit var status: String

    @Column(name = "rejected_by_party_id")
    var rejectedByPartyId: UUID? = null

    @Column(name = "rejection_reason", length = 500)
    var rejectionReason: String? = null

    @Column(name = "rejected_at")
    var rejectedAt: Instant? = null

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @Column(name = "claim_token")
    var claimToken: UUID? = null

    @Column(name = "released_at")
    var releasedAt: Instant? = null

    @Column(name = "release_ref", length = 200)
    var releaseRef: String? = null

    @Column(name = "release_error", length = 500)
    var releaseError: String? = null
}

@Entity
@Table(name = "approval_signatures")
class ApprovalSignatureEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "approval_request_id", nullable = false, updatable = false)
    lateinit var approvalRequestId: UUID

    @Column(name = "party_id", nullable = false, updatable = false)
    lateinit var partyId: UUID

    @Column(name = "sca_challenge_id", nullable = false, updatable = false)
    lateinit var scaChallengeId: UUID

    @Column(name = "signed_at", nullable = false, updatable = false)
    lateinit var signedAt: Instant
}
