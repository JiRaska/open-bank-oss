// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.StatutoryDecisionVerdict
import com.openbank.delegation.domain.model.StatutoryDelegationDecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

data class StatutoryDecisionKey(var operationId: UUID? = null, var actorPartyId: UUID? = null) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

@Entity
@Table(name = "delegation_statutory_decisions")
@IdClass(StatutoryDecisionKey::class)
class StatutoryDelegationDecisionEntity {
    @Id
    @Column(name = "operation_id", nullable = false, updatable = false)
    lateinit var operationId: UUID

    @Id
    @Column(name = "actor_party_id", nullable = false, updatable = false)
    lateinit var actorPartyId: UUID

    @Column(name = "sca_session_id", updatable = false)
    var scaSessionId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false, length = 16)
    lateinit var verdict: StatutoryDecisionVerdict

    @Column(name = "decided_at", nullable = false, updatable = false)
    lateinit var decidedAt: Instant

    fun toDomain() = StatutoryDelegationDecision(operationId, actorPartyId, verdict, scaSessionId, decidedAt)
}
