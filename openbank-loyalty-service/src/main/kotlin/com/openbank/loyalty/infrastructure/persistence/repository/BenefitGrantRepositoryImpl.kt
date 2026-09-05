// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure.persistence.repository

import com.openbank.loyalty.application.port.out.BenefitGrantRepository
import com.openbank.loyalty.domain.BenefitGrant
import com.openbank.loyalty.infrastructure.persistence.entity.BenefitGrantEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * [persistInTransaction] is called from inside `LeafLedgerRepositoryImpl`'s transaction, so the
 * grant and the burn that paid for it commit together. The unique index on
 * `(party_id, idempotency_key)` is the real idempotency guard — [findByIdempotencyKey] is the fast
 * path, and the constraint is what holds under a concurrent retry the read cannot see.
 */
@ApplicationScoped
class BenefitGrantRepositoryImpl :
    BenefitGrantRepository,
    PanacheRepositoryBase<BenefitGrantEntity, UUID> {

    fun persistInTransaction(grant: BenefitGrant) = persist(BenefitGrantEntity.from(grant))

    override suspend fun findByIdempotencyKey(partyId: UUID, key: String): BenefitGrant? = Panache.withSession {
        find("partyId = ?1 and idempotencyKey = ?2", partyId, key).firstResult()
    }.map { it?.toDomain() }.awaitSuspending()
}
