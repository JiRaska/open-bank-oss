// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.persistence.entity

import com.openbank.cardissuance.domain.model.DelegatedCardGrant
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "card_delegation_projection")
class CardDelegationProjectionEntity : PanacheEntityBase() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "card_id", nullable = false)
    lateinit var cardId: UUID

    @Column(name = "grantor_party_id", nullable = false)
    lateinit var grantorPartyId: UUID

    @Column(name = "grantee_party_id", nullable = false)
    lateinit var granteePartyId: UUID

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "card_delegation_projection_caps", joinColumns = [JoinColumn(name = "grant_id")])
    @Column(name = "capability")
    var capabilities: MutableSet<String> = mutableSetOf()

    @Column(name = "valid_from", nullable = false)
    lateinit var validFrom: OffsetDateTime

    @Column(name = "valid_to")
    var validTo: OffsetDateTime? = null

    @Column(name = "active", nullable = false)
    var active: Boolean = true

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: OffsetDateTime

    fun toDomain(): DelegatedCardGrant = DelegatedCardGrant(
        id = id,
        cardId = cardId,
        grantorPartyId = grantorPartyId,
        granteePartyId = granteePartyId,
        capabilities = capabilities.toSet(),
        validFrom = validFrom,
        validTo = validTo,
        active = active,
    )

    companion object {
        fun fromDomain(g: DelegatedCardGrant, now: OffsetDateTime): CardDelegationProjectionEntity =
            CardDelegationProjectionEntity().apply {
                id = g.id
                cardId = g.cardId
                grantorPartyId = g.grantorPartyId
                granteePartyId = g.granteePartyId
                capabilities = g.capabilities.toMutableSet()
                validFrom = g.validFrom
                validTo = g.validTo
                active = g.active
                updatedAt = now
            }
    }
}
