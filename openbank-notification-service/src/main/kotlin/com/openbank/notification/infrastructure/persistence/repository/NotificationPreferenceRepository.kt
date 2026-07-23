// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence.repository

import com.openbank.notification.infrastructure.persistence.entity.NotificationPreferenceEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class NotificationPreferenceRepository : PanacheRepository<NotificationPreferenceEntity> {
    @Inject
    lateinit var clock: Clock

    /**
     * The party's preferences as a `Uni` (not `suspend`) so it composes inside the consumer's
     * reactive `Panache.withTransaction { }` chain, which avoids `suspend` on the Kafka polling
     * thread. Emits null when the party has never set preferences (⇒ all channels on).
     */
    fun findByParty(partyId: UUID): Uni<NotificationPreferenceEntity?> = find("partyId", partyId).firstResult()

    /** Read for the REST layer (coroutine world). */
    suspend fun getByParty(partyId: UUID): NotificationPreferenceEntity? =
        Panache.withSession { findByParty(partyId) }.awaitSuspending()

    /** Upsert the party's preferences and return the persisted row (REST layer). */
    suspend fun upsert(
        partyId: UUID,
        payments: Boolean,
        product: Boolean,
        marketing: Boolean,
    ): NotificationPreferenceEntity = Panache.withTransaction {
        findByParty(partyId).chain { existing ->
            val row = existing ?: NotificationPreferenceEntity().also { it.partyId = partyId }
            row.paymentsPush = payments
            row.productPush = product
            row.marketingPush = marketing
            row.updatedAt = Instant.now(clock)
            if (existing == null) persist(row).map { row } else Uni.createFrom().item(row)
        }
    }.awaitSuspending()
}
