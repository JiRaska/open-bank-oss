// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.entity

import com.openbank.account.domain.model.DelegatedAccessGrant
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "account_delegation_projection")
class DelegationProjectionEntity : PanacheEntityBase() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(name = "grantee_party_id", nullable = false)
    lateinit var granteePartyId: UUID

    @Column(name = "resource_type", nullable = false)
    lateinit var resourceType: String

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "account_delegation_projection_caps", joinColumns = [JoinColumn(name = "grant_id")])
    @Column(name = "capability")
    var capabilities: MutableSet<String> = mutableSetOf()

    @Column(name = "per_tx_limit_amount", precision = 20, scale = 6)
    var perTxLimitAmount: BigDecimal? = null

    @Column(name = "per_tx_limit_currency", length = 3)
    var perTxLimitCurrency: String? = null

    @Column(name = "valid_from", nullable = false)
    lateinit var validFrom: OffsetDateTime

    @Column(name = "valid_to")
    var validTo: OffsetDateTime? = null

    @Column(name = "active", nullable = false)
    var active: Boolean = true

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: OffsetDateTime

    fun toDomain(): DelegatedAccessGrant = DelegatedAccessGrant(
        id = id,
        accountId = accountId,
        granteePartyId = granteePartyId,
        capabilities = capabilities.toSet(),
        resourceType = resourceType,
        perTransactionLimitAmount = perTxLimitAmount,
        perTransactionLimitCurrency = perTxLimitCurrency,
        validFrom = validFrom,
        validTo = validTo,
        active = active,
    )

    companion object {
        fun fromDomain(g: DelegatedAccessGrant, now: OffsetDateTime): DelegationProjectionEntity =
            DelegationProjectionEntity().apply {
                id = g.id
                accountId = g.accountId
                granteePartyId = g.granteePartyId
                resourceType = g.resourceType
                capabilities = g.capabilities.toMutableSet()
                perTxLimitAmount = g.perTransactionLimitAmount
                perTxLimitCurrency = g.perTransactionLimitCurrency
                validFrom = g.validFrom
                validTo = g.validTo
                active = g.active
                updatedAt = now
            }
    }
}
