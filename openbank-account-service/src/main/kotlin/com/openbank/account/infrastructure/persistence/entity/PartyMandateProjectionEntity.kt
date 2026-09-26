// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.infrastructure.persistence.entity

import com.openbank.account.domain.model.PartyMandateProjection
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "account_party_mandate_projection")
class PartyMandateProjectionEntity : PanacheEntityBase() {
    @Id
    @Column(name = "mandate_id", nullable = false, updatable = false)
    lateinit var mandateId: UUID

    @Column(name = "principal_party_id", nullable = false)
    lateinit var principalPartyId: UUID

    @Column(name = "agent_party_id", nullable = false)
    lateinit var agentPartyId: UUID

    @Column(name = "authority", nullable = false)
    lateinit var authority: String

    @Column(name = "required_signatures")
    var requiredSignatures: Int? = null

    @Column(name = "active", nullable = false)
    var active: Boolean = true

    fun toDomain() = PartyMandateProjection(
        mandateId,
        principalPartyId,
        agentPartyId,
        authority,
        requiredSignatures,
        active,
    )

    companion object {
        fun fromDomain(mandate: PartyMandateProjection) = PartyMandateProjectionEntity().apply {
            mandateId = mandate.id
            principalPartyId = mandate.principalPartyId
            agentPartyId = mandate.agentPartyId
            authority = mandate.authority
            requiredSignatures = mandate.requiredSignatures
            active = mandate.active
        }
    }
}
