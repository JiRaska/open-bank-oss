// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.domain.model.TieOutRunRecord
import com.openbank.ledger.domain.model.TieOutRunStatus
import com.openbank.ledger.infrastructure.persistence.entity.TieOutRunEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class PanacheTieOutRunRepository :
    TieOutRunRepository,
    PanacheRepositoryBase<TieOutRunEntity, UUID> {

    override suspend fun save(record: TieOutRunRecord): TieOutRunRecord {
        Panache.withTransaction {
            persist(record.toEntity())
        }.awaitSuspending()
        return record
    }

    override suspend fun findLatest(): TieOutRunRecord? = Panache.withSession {
        find("order by runAt desc").firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findLatestFor(asOf: LocalDate): TieOutRunRecord? = Panache.withSession {
        find("asOf = ?1 order by runAt desc", asOf).firstResult()
    }.awaitSuspending()?.toDomain()

    private fun TieOutRunRecord.toEntity() = TieOutRunEntity().also {
        it.id = id
        it.asOf = asOf
        it.runAt = runAt
        it.status = status.name
        it.accountsChecked = accountsChecked
        it.breaks = breaks
        it.errors = errors
    }

    private fun TieOutRunEntity.toDomain() = TieOutRunRecord(
        id = id,
        asOf = asOf,
        runAt = runAt,
        status = TieOutRunStatus.valueOf(status),
        accountsChecked = accountsChecked,
        breaks = breaks,
        errors = errors,
    )
}
