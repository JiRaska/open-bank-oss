// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationKind
import com.openbank.delegation.domain.model.StatutoryOperationState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "delegation_statutory_operations")
class StatutoryDelegationOperationEntity {
    @Id
    @Column(name = "operation_id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "principal_party_id", nullable = false, updatable = false)
    lateinit var principalPartyId: UUID

    @Column(name = "initiator_party_id", nullable = false, updatable = false)
    lateinit var initiatorPartyId: UUID

    @Column(name = "request_key", nullable = false, updatable = false, length = 200)
    lateinit var requestKey: String

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    lateinit var requestHash: String

    @Column(name = "payload_json", nullable = false, updatable = false, columnDefinition = "text")
    lateinit var payloadJson: String

    @Column(name = "policy_id", nullable = false, updatable = false)
    lateinit var policyId: UUID

    @Column(name = "policy_revision", nullable = false, updatable = false)
    var policyRevision: Long = 0

    @Column(name = "source_case_id", nullable = false, updatable = false)
    lateinit var sourceCaseId: UUID

    @Column(name = "rule_hash", nullable = false, updatable = false, length = 64)
    lateinit var ruleHash: String

    @Column(name = "rule_snapshot_json", nullable = false, updatable = false, columnDefinition = "text")
    lateinit var ruleSnapshotJson: String

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant

    @Column(name = "expires_at", nullable = false, updatable = false)
    lateinit var expiresAt: Instant

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    lateinit var state: StatutoryOperationState

    @Column(name = "executed_at")
    var executedAt: Instant? = null

    @Column(name = "grant_id")
    var grantId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_kind", nullable = false, updatable = false, length = 16)
    lateinit var operationKind: StatutoryOperationKind

    @Column(name = "target_grant_id", updatable = false)
    var targetGrantId: UUID? = null

    @Column(name = "expected_lifecycle_revision", updatable = false)
    var expectedLifecycleRevision: Long? = null

    fun toDomain() = StatutoryDelegationOperation(
        id = id,
        principalPartyId = principalPartyId,
        initiatorPartyId = initiatorPartyId,
        requestKey = requestKey,
        requestHash = requestHash.trim(),
        payloadJson = payloadJson,
        policyId = policyId,
        policyRevision = policyRevision,
        sourceCaseId = sourceCaseId,
        ruleHash = ruleHash.trim(),
        ruleSnapshotJson = ruleSnapshotJson,
        createdAt = createdAt,
        expiresAt = expiresAt,
        state = state,
        executedAt = executedAt,
        grantId = grantId,
        operationKind = operationKind,
        targetGrantId = targetGrantId,
        expectedLifecycleRevision = expectedLifecycleRevision,
    )
}
