// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.infrastructure.persistence.repository

import com.openbank.statement.application.port.out.AccountRegistry
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.util.UUID

@ApplicationScoped
class AccountRegistryRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val clock: Clock,
) : AccountRegistry {

    /**
     * Idempotent insert: `ON CONFLICT DO NOTHING` makes a redelivery (at-least-once Kafka) or a
     * cold-start replay a no-op at the database level, with no read-then-write race.
     */
    @WithTransaction
    override fun upsertOpen(accountId: UUID, partyId: UUID, currency: String): Uni<Void> = sf.withTransaction { s ->
        s.createNativeQuery<Any>(
            "INSERT INTO account_registry (account_id, party_id, currency, registered_at) " +
                "VALUES (:a, :p, :c, :t) ON CONFLICT (account_id) DO NOTHING",
        )
            .setParameter("a", accountId)
            .setParameter("p", partyId)
            .setParameter("c", currency)
            .setParameter("t", clock.instant())
            .executeUpdate()
    }.replaceWithVoid()

    @WithSession
    override fun allAccountIds(): Uni<List<UUID>> = sf.withSession { s ->
        s.createQuery("SELECT a.accountId FROM AccountRegistryEntity a", UUID::class.java)
            .resultList
    }.map { it.toList() }
}
