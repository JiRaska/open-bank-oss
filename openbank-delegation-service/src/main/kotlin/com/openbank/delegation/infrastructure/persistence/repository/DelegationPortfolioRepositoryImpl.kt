// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.infrastructure.persistence.repository

import com.openbank.delegation.application.port.out.DelegationPortfolioRepository
import com.openbank.delegation.domain.model.DelegationPortfolio
import com.openbank.delegation.infrastructure.persistence.entity.DelegationPortfolioEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class DelegationPortfolioRepositoryImpl :
    DelegationPortfolioRepository,
    PanacheRepository<DelegationPortfolioEntity> {
    override suspend fun save(portfolio: DelegationPortfolio): DelegationPortfolio = Panache.withTransaction {
        Panache.getSession().flatMap { it.merge(DelegationPortfolioEntity.fromDomain(portfolio)) }
    }.awaitSuspending().toDomain()

    override suspend fun findById(id: UUID): DelegationPortfolio? = Panache.withSession {
        find("id", id).firstResult<DelegationPortfolioEntity>()
    }.awaitSuspending()?.toDomain()

    override suspend fun findByOwner(ownerPartyId: UUID): List<DelegationPortfolio> = Panache.withSession {
        find("ownerPartyId = ?1 order by name", ownerPartyId).list<DelegationPortfolioEntity>()
    }.awaitSuspending().map { it.toDomain() }
}
