// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "statutory_delegation_operations")
class StatutoryDelegationOperationEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "action", nullable = false, updatable = false, length = 8)
    lateinit var action: String

    @Column(name = "delegation_id", nullable = false, updatable = false)
    lateinit var delegationId: UUID

    @Column(name = "expected_lifecycle_revision", updatable = false)
    var expectedLifecycleRevision: Long? = null

    @Column(name = "principal_party_id", nullable = false, updatable = false)
    lateinit var principalPartyId: UUID

    @Column(name = "initiator_party_id", nullable = false, updatable = false)
    lateinit var initiatorPartyId: UUID

    @Column(name = "request_key", nullable = false, updatable = false, length = 128)
    lateinit var requestKey: String

    @Column(name = "rule_snapshot", nullable = false, updatable = false, columnDefinition = "TEXT")
    lateinit var ruleSnapshot: String

    @Column(name = "frozen_payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    lateinit var frozenPayload: String

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant

    @Column(name = "expires_at", nullable = false, updatable = false)
    lateinit var expiresAt: Instant

    @Column(name = "status", nullable = false, length = 12)
    lateinit var status: String

    @Column(name = "closed_by")
    var closedBy: UUID? = null
}

@Entity
@Table(name = "statutory_delegation_signatures")
class StatutoryDelegationSignatureEntity {
    @Id
    @Column(name = "sca_challenge_id", nullable = false, updatable = false)
    lateinit var scaChallengeId: UUID

    @Column(name = "operation_id", nullable = false, updatable = false)
    lateinit var operationId: UUID

    @Column(name = "actor_party_id", nullable = false, updatable = false)
    lateinit var actorPartyId: UUID

    @Column(name = "signed_at", nullable = false, updatable = false)
    lateinit var signedAt: Instant
}
