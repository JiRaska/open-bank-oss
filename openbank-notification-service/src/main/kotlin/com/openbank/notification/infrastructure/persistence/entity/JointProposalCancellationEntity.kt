// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Immutable local tombstone; delivery state is not the authority for whether a proposal is open. */
@Entity
@Table(name = "joint_proposal_cancellations")
class JointProposalCancellationEntity {
    @Id
    @Column(name = "operation_id", nullable = false)
    lateinit var operationId: UUID

    @Column(name = "principal_party_id", nullable = false)
    lateinit var principalPartyId: UUID

    @Column(name = "actor_id", nullable = false)
    lateinit var actorId: UUID

    @Column(name = "operation_kind", nullable = false)
    lateinit var operationKind: String

    @Column(name = "cancelled_at", nullable = false)
    lateinit var cancelledAt: Instant
}
