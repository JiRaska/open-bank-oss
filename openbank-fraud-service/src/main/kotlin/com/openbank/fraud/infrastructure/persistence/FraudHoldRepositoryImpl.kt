// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.openbank.fraud.application.port.out.FraudHoldRecord
import com.openbank.fraud.application.port.out.FraudHoldRepository
import com.openbank.fraud.infrastructure.persistence.entity.FraudHoldEntity
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class FraudHoldRepositoryImpl :
    FraudHoldRepository,
    PanacheRepository<FraudHoldEntity> {

    override suspend fun findActive(partyId: UUID): FraudHoldRecord? = Panache.withSession {
        find("partyId = ?1 and active = true", partyId).firstResult()
    }.awaitSuspending()?.toRecord()

    override suspend fun findExpiredActive(now: Instant): List<FraudHoldRecord> = Panache.withSession {
        find("active = true and expiresAt < ?1", now).list()
    }.awaitSuspending().map { it.toRecord() }

    // find-then-persist-or-update on the party_id business key (never a naked persist() on a
    // fresh entity when one might already exist — see the entity's KDoc on the assigned-id/
    // INSERT-only pitfall). Uni, not suspend: the caller composes this with the outbox write in
    // one transaction (ADR-0050), so it must return a chainable Uni, not await internally.
    override fun raise(
        partyId: UUID,
        accountId: UUID,
        reason: String,
        ruleVersion: String,
        setAt: Instant,
        expiresAt: Instant,
    ): Uni<Void> = find("partyId = ?1", partyId).firstResult().chain { existing: FraudHoldEntity? ->
        if (existing != null) {
            existing.accountId = accountId
            existing.active = true
            existing.reason = reason
            existing.ruleVersion = ruleVersion
            existing.setAt = setAt
            existing.expiresAt = expiresAt
            Panache.getSession().flatMap { it.merge(existing) }
        } else {
            val entity = FraudHoldEntity()
            entity.id = Ids.newId()
            entity.partyId = partyId
            entity.accountId = accountId
            entity.active = true
            entity.reason = reason
            entity.ruleVersion = ruleVersion
            entity.setAt = setAt
            entity.expiresAt = expiresAt
            persist(entity)
        }
    }.replaceWithVoid()

    override fun clear(partyId: UUID): Uni<Void> =
        update("active = false where partyId = ?1", partyId).replaceWithVoid()
}

private fun FraudHoldEntity.toRecord() = FraudHoldRecord(
    partyId = partyId,
    accountId = accountId,
    reason = reason,
    ruleVersion = ruleVersion,
    setAt = setAt,
    expiresAt = expiresAt,
)
