// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.persistence.repository

import com.openbank.party.application.port.out.MarketingConsentTracking
import com.openbank.party.application.port.out.MarketingConsentTrackingRepository
import com.openbank.party.infrastructure.persistence.entity.PartyMarketingConsentEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class MarketingConsentTrackingRepositoryImpl :
    MarketingConsentTrackingRepository,
    PanacheRepository<PartyMarketingConsentEntity> {

    override suspend fun findByPartyId(partyId: UUID): MarketingConsentTracking? =
        Panache.withSession { find("partyId", partyId).firstResult() }.awaitSuspending()?.toDomain()

    // Insert-or-update via find-then-mutate — PartyMarketingConsentEntity's PanacheEntity surrogate
    // id makes this Hibernate-managed dirty-checking (safe for update), not the persist()-is-
    // INSERT-only pitfall an application-assigned @Id would hit.
    override suspend fun upsert(tracking: MarketingConsentTracking) {
        Panache.withTransaction {
            find("partyId", tracking.partyId).firstResult().chain { existing ->
                if (existing != null) {
                    existing.consentId = tracking.consentId
                    existing.grantedAt = tracking.grantedAt
                    Uni.createFrom().voidItem()
                } else {
                    val e = PartyMarketingConsentEntity().also {
                        it.partyId = tracking.partyId
                        it.consentId = tracking.consentId
                        it.grantedAt = tracking.grantedAt
                    }
                    persist(e).replaceWithVoid()
                }
            }
        }.awaitSuspending()
    }

    // Scoped conditional DELETE, not find-then-check-then-delete: a delete WHERE clause matching
    // both partyId and consentId is atomic, so a concurrent upsert (re-grant) racing this delete
    // cannot be lost — either the delete matches the row that existed at delete time, or (if a
    // re-grant already changed consentId) it matches zero rows and correctly no-ops.
    override suspend fun deleteIfMatches(partyId: UUID, consentId: UUID): Boolean {
        val deletedCount = Panache.withTransaction {
            delete("partyId = ?1 and consentId = ?2", partyId, consentId)
        }.awaitSuspending()
        return deletedCount > 0
    }

    private fun PartyMarketingConsentEntity.toDomain() =
        MarketingConsentTracking(partyId = partyId, consentId = consentId, grantedAt = grantedAt)
}
