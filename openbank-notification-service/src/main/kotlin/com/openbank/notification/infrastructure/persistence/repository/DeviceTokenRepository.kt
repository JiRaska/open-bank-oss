// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence.repository

import com.openbank.notification.domain.model.DeviceRegistration
import com.openbank.notification.infrastructure.persistence.entity.DeviceTokenEntity
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
class DeviceTokenRepository : PanacheRepository<DeviceTokenEntity> {
    @Inject
    lateinit var clock: Clock

    /**
     * Active device tokens for a party — the PUSH fan-out target set.
     *
     * Returns a `Uni` (not `suspend`) so it composes inside [NotificationConsumer]'s reactive
     * `Panache.withTransaction { }` chain, which deliberately avoids `suspend` on the Kafka
     * polling thread (see the consumer's doc comment).
     */
    fun findActiveByParty(partyId: UUID): Uni<List<DeviceTokenEntity>> =
        find("partyId = ?1 and status = ?2", partyId, "ACTIVE").list()

    /** All tokens for a party, for the REST listing. */
    suspend fun listByParty(partyId: UUID): List<DeviceTokenEntity> =
        Panache.withSession { find("partyId", partyId).list() }.awaitSuspending()

    /**
     * Register (or refresh) a device token. Idempotent on (platform, token): a re-registration
     * of the same provider token re-binds it to the current party and re-activates it rather
     * than creating a duplicate row. Returns the persisted entity.
     */
    suspend fun register(reg: DeviceRegistration): DeviceTokenEntity = Panache.withTransaction {
        find("platform = ?1 and token = ?2", reg.platform.name, reg.token).firstResult()
            .flatMap { existing ->
                val now = Instant.now(clock)
                if (existing != null) {
                    existing.partyId = reg.partyId
                    existing.appInstance = reg.appInstance
                    existing.appVersion = reg.appVersion
                    existing.osVersion = reg.osVersion
                    existing.status = "ACTIVE"
                    existing.lastUsedAt = now
                    existing.refreshedAt = now
                    existing.updatedAt = now
                    Uni.createFrom().item(existing)
                } else {
                    val entity = DeviceTokenEntity().also {
                        it.deviceId = UUID.randomUUID()
                        it.partyId = reg.partyId
                        it.appInstance = reg.appInstance
                        it.platform = reg.platform.name
                        it.token = reg.token
                        it.appVersion = reg.appVersion
                        it.osVersion = reg.osVersion
                        it.status = "ACTIVE"
                        it.lastUsedAt = now
                        it.registeredAt = now
                        it.refreshedAt = now
                        it.createdAt = now
                        it.updatedAt = now
                    }
                    persist(entity).map { entity }
                }
            }
    }.awaitSuspending()

    /**
     * Retire device tokens the provider rejected (UNREGISTERED / INVALID_TOKEN) so they drop
     * out of future fan-out. Uni-based for use inside the consumer's reactive chain.
     */
    fun invalidate(deviceIds: Collection<UUID>): Uni<Int> {
        if (deviceIds.isEmpty()) return Uni.createFrom().item(0)
        return update(
            "status = 'INVALID', updatedAt = ?1 where deviceId in ?2",
            Instant.now(clock),
            deviceIds,
        )
    }

    /**
     * Deactivate a single device token on explicit logout or admin revocation.
     * If [partyId] is provided the update is scoped to that party's tokens only —
     * customer-edge injects the authoritative partyId from the JWT before calling this.
     * Returns true if the token was ACTIVE and is now INACTIVE; false if not found or already inactive.
     */
    suspend fun deactivate(deviceId: UUID, partyId: UUID? = null): Boolean = Panache.withTransaction {
        val now = Instant.now(clock)
        if (partyId != null) {
            update(
                "status = 'INACTIVE', updatedAt = ?1 where deviceId = ?2 and partyId = ?3 and status = 'ACTIVE'",
                now,
                deviceId,
                partyId,
            ).map { it > 0 }
        } else {
            update(
                "status = 'INACTIVE', updatedAt = ?1 where deviceId = ?2 and status = 'ACTIVE'",
                now,
                deviceId,
            ).map { it > 0 }
        }
    }.awaitSuspending()

    /**
     * Mark all tokens not refreshed within the TTL window as INACTIVE (ADR-0135 §2).
     * Prefers [refreshedAt] (explicit app-foreground refresh) when available; falls back to
     * [lastUsedAt] for V6 rows backfilled before this column existed; falls back further to
     * [createdAt] (always non-null) so zombie tokens that were registered but never used are
     * swept rather than silently retained forever.
     * Uni-based for reactive subscription inside [DeviceTokenSweepJob].
     */
    fun sweepStale(threshold: Instant): Uni<Int> = Panache.withTransaction {
        update(
            "status = 'INACTIVE', updatedAt = ?1 where status = 'ACTIVE' and COALESCE(refreshedAt, lastUsedAt, createdAt) < ?2",
            Instant.now(clock),
            threshold,
        )
    }

    suspend fun deleteByPartyId(partyId: UUID): Long =
        Panache.withTransaction { delete("partyId", partyId) }.awaitSuspending()
}
