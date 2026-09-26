// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.DelegationPortfolio
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
@Table(name = "delegation_portfolios")
class DelegationPortfolioEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID

    @Column(name = "owner_party_id", nullable = false)
    lateinit var ownerPartyId: UUID

    @Column(name = "name", nullable = false, length = 100)
    lateinit var name: String

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "delegation_portfolio_accounts", joinColumns = [JoinColumn(name = "portfolio_id")])
    @Column(name = "account_id", nullable = false)
    var accountIds: MutableSet<UUID> = mutableSetOf()

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: OffsetDateTime

    fun toDomain() = DelegationPortfolio(id, ownerPartyId, name, accountIds, createdAt, updatedAt)

    companion object {
        fun fromDomain(value: DelegationPortfolio) = DelegationPortfolioEntity().apply {
            id = value.id
            ownerPartyId = value.ownerPartyId
            name = value.name
            accountIds = value.accountIds.toMutableSet()
            createdAt = value.createdAt
            updatedAt = value.updatedAt
        }
    }
}
